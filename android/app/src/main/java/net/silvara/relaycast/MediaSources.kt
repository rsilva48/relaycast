@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package net.silvara.relaycast

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Xml
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session as SmbSession
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.Security
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.xmlpull.v1.XmlPullParser

/**
 * A place with the user's own media. Nothing here goes through a Relay
 * Server: the app talks to the source directly.
 *
 * - `local`: one file on this device (a persistable content URI).
 * - `folder`: a folder on this device (a persistable document tree URI).
 * - `http`: one direct link (MP4, MKV, HLS…), optionally with Basic auth.
 * - `webdav`: a WebDAV folder, optionally with Basic auth.
 * - `smb`: a shared folder (SMB 2/3), `smb://server/share/path`.
 * - `sftp`: an SFTP folder, `sftp://server[:port]/path`, with the server's
 *   pinned public key (never trusted on first use).
 */
@Serializable
internal data class MediaSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String,
    val address: String,
    val username: String = "",
    val password: String = "",
    val hostKey: String = "",
) {
    val browsable: Boolean get() = type in listOf("folder", "webdav", "smb", "sftp")
}

/** A folder or a file inside a source; `path` is relative to the source (a document id for `folder`). */
internal data class SourceEntry(val name: String, val path: String, val directory: Boolean)

/** The sources of this device, encrypted with the Android Keystore (they can hold passwords). */
internal class MediaSources(context: Context) {
    private val secrets = PhoneIdentity(context.applicationContext, "sources")
    private val serializer = ListSerializer(MediaSource.serializer())
    fun load(): List<MediaSource> = secrets.token()?.let { runCatching { RelayClient.json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
    fun save(values: List<MediaSource>) = secrets.saveToken(RelayClient.json.encodeToString(serializer, values))
}

/** Where each file was left, per source and path, to resume it. */
internal object SourcePositions {
    private fun prefs(context: Context) = context.getSharedPreferences("source_positions", Context.MODE_PRIVATE)
    private fun key(source: MediaSource, path: String) = "${source.id}|$path"
    fun get(context: Context, source: MediaSource, path: String): Long = prefs(context).getLong(key(source, path), 0L)
    fun save(context: Context, source: MediaSource, path: String, positionMs: Long, durationMs: Long) {
        // Near the end counts as watched: next time it starts over.
        val done = durationMs > 0 && (durationMs - positionMs < 30_000 || positionMs > durationMs * 0.95)
        prefs(context).edit().apply {
            if (done || positionMs < 5_000) remove(key(source, path)) else putLong(key(source, path), positionMs)
        }.apply()
    }
    fun forget(context: Context, source: MediaSource) {
        val prefs = prefs(context)
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith("${source.id}|") }.forEach(::remove) }.apply()
    }
}

/** A file to play: its URI and the subtitles found next to it. */
internal data class SourcePlayback(val source: MediaSource, val path: String, val title: String, val uri: Uri, val subtitles: List<MediaItem.SubtitleConfiguration>)

internal class SourceException(message: String) : Exception(message)

internal object SourceFiles {
    private val video = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv", "3gp", "m3u8")
    private val audio = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
    private val subtitles = mapOf("srt" to MimeTypes.APPLICATION_SUBRIP, "vtt" to MimeTypes.TEXT_VTT, "ass" to MimeTypes.TEXT_SSA, "ssa" to MimeTypes.TEXT_SSA)

    /** The custom scheme the SMB and SFTP data sources answer. */
    const val SCHEME = "relaycast-source"

    private fun extension(name: String) = name.substringAfterLast('.', "").lowercase()
    fun playable(name: String) = extension(name) in video || extension(name) in audio
    private fun visible(entry: SourceEntry) = entry.name !in listOf(".", "..") && !entry.name.startsWith(".") && (entry.directory || playable(entry.name))

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        // Credentials never follow a redirect to another endpoint.
        .followRedirects(false).followSslRedirects(false).build()

    /** Checks what the user typed; the message says what to fix. */
    fun validate(source: MediaSource) {
        if (source.name.isBlank()) throw SourceException(Strings.get(R.string.source_error_name))
        if (source.type in listOf("local", "folder")) return
        val uri = runCatching { URI(source.address) }.getOrNull() ?: throw SourceException(Strings.get(R.string.source_error_address))
        if (uri.userInfo != null) throw SourceException(Strings.get(R.string.source_error_credentials_in_address))
        if (uri.host.isNullOrBlank() || uri.fragment != null) throw SourceException(Strings.get(R.string.source_error_address))
        when (source.type) {
            "smb" -> if (uri.scheme != "smb" || uri.path.trim('/').isEmpty()) throw SourceException(Strings.get(R.string.source_error_smb))
            "sftp" -> {
                if (uri.scheme != "sftp") throw SourceException(Strings.get(R.string.source_error_scheme))
                val parts = source.hostKey.trim().split(Regex("\\s+"))
                if (parts.size < 2 || !parts[0].startsWith("ssh-") && !parts[0].startsWith("ecdsa-")) throw SourceException(Strings.get(R.string.source_error_host_key))
            }
            else -> if (uri.scheme !in listOf("http", "https")) throw SourceException(Strings.get(R.string.source_error_scheme))
        }
    }

    private fun remotePath(source: MediaSource, path: String): String {
        if (path.startsWith('/') || path.split('/').any { it == ".." || it == "." }) throw SourceException("Invalid path")
        return (URI(source.address).path.trimEnd('/') + "/" + path).trimEnd('/').ifEmpty { "/" }
    }

    private fun webUrl(source: MediaSource, path: String): String {
        val base = URI(source.address.trimEnd('/') + "/")
        val encoded = path.split('/').filter(String::isNotEmpty).joinToString("/") { Uri.encode(it) }
        return base.resolve(encoded).toASCIIString()
    }

    private fun basicAuth(source: MediaSource): String? = source.username.takeIf { it.isNotEmpty() }?.let { Credentials.basic(it, source.password) }

    private suspend fun all(context: Context, source: MediaSource, path: String): List<SourceEntry> = withContext(Dispatchers.IO) {
        try {
            when (source.type) {
                "folder" -> {
                    val tree = Uri.parse(source.address)
                    val parent = path.ifEmpty { DocumentsContract.getTreeDocumentId(tree) }
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
                    val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
                    context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add(SourceEntry(cursor.getString(1).orEmpty(), cursor.getString(0), cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
                        }
                    } ?: emptyList()
                }
                "smb" -> ListingConnection.use(source, { SmbConnection(source) }) { smb ->
                    smb.share.list(smb.join(path)).map {
                        SourceEntry(it.fileName, listOf(path, it.fileName).filter(String::isNotEmpty).joinToString("/"), it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L)
                    }
                }
                "sftp" -> ListingConnection.use(source, { SftpConnection(context, source) }) { sftp ->
                    sftp.client.ls(remotePath(source, path)).map { SourceEntry(it.name, listOf(path, it.name).filter(String::isNotEmpty).joinToString("/"), it.isDirectory) }
                }
                "webdav" -> webdav(source, path)
                else -> emptyList()
            }
        } catch (error: SourceException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("RelaycastSources", "Listing ${source.type} failed", error)
            throw SourceException(describe(error))
        }
    }

    private fun webdav(source: MediaSource, path: String): List<SourceEntry> {
        val url = webUrl(source, path).trimEnd('/') + "/"
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        val request = Request.Builder().url(url).header("Depth", "1").method("PROPFIND", body.toRequestBody()).apply { basicAuth(source)?.let { header("Authorization", it) } }.build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 401) throw SourceException(Strings.get(R.string.source_error_login))
            if (response.code != 207) throw SourceException("WebDAV HTTP ${response.code}")
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(response.body.byteStream(), null)
            val base = URI(source.address.trimEnd('/') + "/")
            val current = path.trim('/')
            val entries = mutableListOf<SourceEntry>()
            var href = ""
            var directory = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                    "response" -> { href = ""; directory = false }
                    "href" -> href = parser.nextText().trim()
                    "collection" -> directory = true
                }
                if (parser.eventType == XmlPullParser.END_TAG && parser.name == "response" && href.isNotBlank()) {
                    val resolved = runCatching { URI(url).resolve(href) }.getOrNull()
                    if (resolved != null && resolved.scheme == base.scheme && resolved.authority == base.authority && resolved.path.startsWith(base.path)) {
                        val relative = resolved.path.removePrefix(base.path).trim('/')
                        if (relative.isNotEmpty() && relative != current && relative.substringBeforeLast('/', "") == current) {
                            entries += SourceEntry(relative.substringAfterLast('/'), relative, directory)
                        }
                    }
                }
                parser.next()
            }
            entries
        }
    }

    /** What to play for a file of a source (or the source itself for `local` and `http`), with its subtitles. */
    suspend fun playback(context: Context, source: MediaSource, entry: SourceEntry?): SourcePlayback {
        val path = entry?.path.orEmpty()
        val title = entry?.name ?: source.name
        val uri = uriFor(source, path)
        // Subtitles next to the video: "Name.srt", "Name.es.srt", "Name.en.forced.vtt"…
        val subtitles = if (entry == null || !source.browsable) emptyList() else runCatching {
            val folder = if (source.type == "folder") null else path.substringBeforeLast('/', "")
            val siblings = if (source.type == "folder") parentListing(entry) else all(context, source, folder!!)
            val base = entry.name.substringBeforeLast('.')
            val files = siblings.filter { !it.directory && extension(it.name) in subtitles && it.name.startsWith(base) }
            fun language(file: SourceEntry) = file.name.removePrefix(base).substringBeforeLast('.').trim('.', ' ', '_', '-').substringBefore('.').takeIf { it.length in 2..3 }?.lowercase()
            // Shown from the start: the one in the device's language, else the first.
            val device = java.util.Locale.getDefault().language
            val chosen = files.firstOrNull { language(it) == device } ?: files.firstOrNull()
            files.map { file ->
                val middle = file.name.removePrefix(base).substringBeforeLast('.').trim('.', ' ', '_', '-')
                MediaItem.SubtitleConfiguration.Builder(uriFor(source, file.path))
                    .setMimeType(subtitles.getValue(extension(file.name)))
                    .setLanguage(language(file))
                    .setLabel(middle.ifEmpty { file.name })
                    .setSelectionFlags(if (file == chosen) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }
        }.getOrDefault(emptyList())
        return SourcePlayback(source, path, title, uri, subtitles)
    }

    /** The last device folder listed: a document's siblings for its subtitles. */
    private var lastFolderListing: Pair<String, List<SourceEntry>>? = null

    private fun parentListing(entry: SourceEntry): List<SourceEntry> =
        lastFolderListing?.takeIf { (_, entries) -> entries.any { it.path == entry.path } }?.second ?: emptyList()

    /**
     * Folders first, then the playable files. A device folder's full listing
     * (subtitles included) is remembered for [playback], which can't list a
     * document's parent.
     */
    suspend fun list(context: Context, source: MediaSource, path: String): List<SourceEntry> {
        val everything = all(context, source, path)
        if (source.type == "folder") lastFolderListing = path to everything
        return everything.filter(::visible).sortedWith(compareBy<SourceEntry> { !it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun uriFor(source: MediaSource, path: String): Uri = when (source.type) {
        "local" -> Uri.parse(source.address)
        "folder" -> DocumentsContract.buildDocumentUriUsingTree(Uri.parse(source.address), path)
        "http" -> Uri.parse(source.address)
        "webdav" -> Uri.parse(webUrl(source, path))
        else -> Uri.Builder().scheme(SCHEME).authority(source.id).path("/$path").build()
    }

    /** Reads each source the way it needs: content URIs, HTTP with its credentials, or SMB and SFTP by ranges. */
    fun dataSourceFactory(context: Context, source: MediaSource): SourceDataSourceFactory = SourceDataSourceFactory(context.applicationContext, source)

    fun describe(error: Throwable): String = when (error) {
        is SourceException -> error.message.orEmpty()
        is net.schmizz.sshj.transport.TransportException -> Strings.get(R.string.source_error_host_key_mismatch)
        is net.schmizz.sshj.userauth.UserAuthException -> Strings.get(R.string.source_error_login)
        is com.hierynomus.mssmb2.SMBApiException -> if (error.statusCode == 0xC000006DL) Strings.get(R.string.source_error_login) else error.message.orEmpty()
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> Strings.get(R.string.source_error_unreachable)
        is PlaybackException -> error.localizedMessage.orEmpty()
        else -> error.message ?: error.javaClass.simpleName
    }

    /** sshj needs the full BouncyCastle; Android's own "BC" provider is a trimmed copy. */
    @Volatile private var bouncyCastleReady = false
    fun ensureBouncyCastle() {
        if (bouncyCastleReady) return
        synchronized(this) {
            if (bouncyCastleReady) return
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            bouncyCastleReady = true
        }
    }
}

/**
 * The connection used to browse one SMB or SFTP source, kept for a couple of
 * minutes: walking into folders doesn't log in again each time. A failed
 * call drops it and tries once more with a new one.
 */
internal object ListingConnection {
    private const val IDLE_MS = 120_000L
    private var sourceId: String? = null
    private var connection: Closeable? = null
    private var lastUsed = 0L

    @Suppress("UNCHECKED_CAST")
    fun <C : Closeable, T> use(source: MediaSource, open: () -> C, action: (C) -> T): T = synchronized(this) {
        val fresh = sourceId != source.id || connection == null || System.currentTimeMillis() - lastUsed > IDLE_MS
        if (fresh) drop()
        val current = (connection as C?) ?: open().also { connection = it; sourceId = source.id }
        try {
            action(current).also { lastUsed = System.currentTimeMillis() }
        } catch (error: Exception) {
            drop()
            if (fresh) throw error
            // A kept connection may have been closed by the server meanwhile.
            val again = open().also { connection = it; sourceId = source.id }
            action(again).also { lastUsed = System.currentTimeMillis() }
        }
    }

    /** Closes the kept connection (also when its source is removed). */
    fun drop() = synchronized(this) {
        runCatching { connection?.close() }
        connection = null
        sourceId = null
    }
}

/** One authenticated SMB share, open while it is used. */
internal class SmbConnection(source: MediaSource) : Closeable {
    private val client = SMBClient(SmbConfig.builder().withSecurityProvider(BCSecurityProvider()).withTimeout(30, TimeUnit.SECONDS).build())
    private val connection: Connection
    private val session: SmbSession
    val share: DiskShare
    private val root: String

    init {
        val uri = URI(source.address)
        try {
            connection = client.connect(uri.host, if (uri.port > 0) uri.port else 445)
            val domain = source.username.substringBefore('\\', "").takeIf { source.username.contains('\\') }.orEmpty()
            val user = source.username.substringAfter('\\')
            session = connection.authenticate(if (user.isEmpty()) AuthenticationContext.guest() else AuthenticationContext(user, source.password.toCharArray(), domain))
            val parts = uri.path.trim('/').split('/', limit = 2)
            share = session.connectShare(parts[0]) as DiskShare
            root = parts.getOrElse(1) { "" }.trim('/').replace('/', '\\')
        } catch (error: Exception) {
            client.close()
            throw error
        }
    }

    /** The share-relative Windows path of a source path. */
    fun join(path: String): String {
        if (path.split('/').any { it == ".." || it == "." }) throw SourceException("Invalid path")
        return listOf(root, path.trim('/').replace('/', '\\')).filter(String::isNotEmpty).joinToString("\\")
    }

    fun open(path: String): com.hierynomus.smbj.share.File =
        share.openFile(join(path), setOf(AccessMask.GENERIC_READ), setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        client.close()
    }
}

/** One SFTP session that only accepts the source's pinned host key. */
internal class SftpConnection(context: Context, source: MediaSource) : Closeable {
    private val ssh = SSHClient()
    val client: SFTPClient

    init {
        SourceFiles.ensureBouncyCastle()
        val uri = URI(source.address)
        val port = if (uri.port > 0) uri.port else 22
        val knownHosts = File.createTempFile("source-host-", ".pub", context.cacheDir)
        try {
            knownHosts.writeText("${if (port == 22) uri.host else "[${uri.host}]:$port"} ${source.hostKey.trim()}\n")
            ssh.connectTimeout = 20_000
            ssh.timeout = 30_000
            ssh.loadKnownHosts(knownHosts)
            ssh.connect(uri.host, port)
            ssh.authPassword(source.username, source.password)
            client = ssh.newSFTPClient()
        } catch (error: Exception) {
            runCatching { ssh.close() }
            throw error
        } finally {
            knownHosts.delete()
        }
    }

    override fun close() {
        runCatching { client.close() }
        runCatching { ssh.close() }
    }
}

/**
 * The data sources of one playing source. SMB and SFTP share one connection
 * for the video and its subtitles, opened on first use and closed by [close]
 * when the player goes away.
 */
internal class SourceDataSourceFactory(private val context: Context, private val source: MediaSource) : DataSource.Factory, Closeable {
    private val lock = Any()
    private var smb: SmbConnection? = null
    private var sftp: SftpConnection? = null

    override fun createDataSource(): DataSource = when (source.type) {
        "smb" -> RangeDataSource { path ->
            val share = synchronized(lock) { smb ?: SmbConnection(source).also { smb = it } }
            val file = share.open(path)
            object : RangeFile {
                override val length = file.fileInformation.standardInformation.endOfFile
                override fun read(offset: Long, buffer: ByteArray, at: Int, count: Int) = file.read(buffer, offset, at, count)
                override fun close() = file.close()
            }
        }
        "sftp" -> RangeDataSource { path ->
            val session = synchronized(lock) { sftp ?: SftpConnection(context, source).also { sftp = it } }
            val file: RemoteFile = session.client.open(URI(source.address).path.trimEnd('/') + "/" + path)
            object : RangeFile {
                override val length = file.length()
                override fun read(offset: Long, buffer: ByteArray, at: Int, count: Int) = file.read(offset, buffer, at, count)
                override fun close() = file.close()
            }
        }
        "http", "webdav" -> {
            val http = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(20_000)
                .setReadTimeoutMs(45_000)
                .setAllowCrossProtocolRedirects(false)
                .apply { source.username.takeIf { it.isNotEmpty() }?.let { setDefaultRequestProperties(mapOf("Authorization" to Credentials.basic(it, source.password))) } }
            http.createDataSource()
        }
        else -> DefaultDataSource.Factory(context).createDataSource()
    }

    override fun close() {
        synchronized(lock) {
            smb?.close(); smb = null
            sftp?.close(); sftp = null
        }
    }
}

/** A remote file read by offsets (SMB, SFTP). */
internal interface RangeFile : Closeable {
    val length: Long
    fun read(offset: Long, buffer: ByteArray, at: Int, count: Int): Int
}

/** A Media3 data source over a [RangeFile]: seeking only moves the offset. */
internal class RangeDataSource(private val open: (String) -> RangeFile) : BaseDataSource(true) {
    private var file: RangeFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val path = dataSpec.uri.path.orEmpty().removePrefix("/")
        val current = try {
            open(path)
        } catch (error: Exception) {
            throw DataSourceException(IOException(SourceFiles.describe(error), error), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        file = current
        if (dataSpec.position > current.length) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else current.length - position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = try {
            file!!.read(position, buffer, offset, minOf(length.toLong(), remaining, 1L shl 20).toInt())
        } catch (error: Exception) {
            throw DataSourceException(IOException(SourceFiles.describe(error), error), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (count <= 0) return C.RESULT_END_OF_INPUT
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            file?.close()
        } catch (_: Exception) {
        } finally {
            file = null
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
