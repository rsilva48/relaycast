"""Creates the relay's setup QR for Relaycast.

The QR holds relaycast://provision?bridge=<address>&admin=<administrator
token>: whoever scans it in the app (setup assistant, or Settings > Scan
code) gets the relay configured and, with the token, a new account. The
token is typed without being shown and never saved anywhere but the image.

Usage: double-click scripts/setup-qr.cmd, or run `py -3 scripts/setup-qr.py`.
"""

import argparse
import getpass
import os
import subprocess
import sys
import urllib.parse
from pathlib import Path


def ensure_qrcode():
    """The qrcode module for this very Python (several may be installed)."""
    try:
        import qrcode  # noqa: F401
    except ImportError:
        print("Instalando el módulo qrcode (sólo la primera vez)…")
        subprocess.check_call([sys.executable, "-m", "pip", "install", "--user", "--quiet", "qrcode[pil]"])
    import qrcode
    return qrcode


def canonical_relay(text):
    text = text.strip().rstrip("/")
    if not text:
        return None
    if "://" not in text:
        text = "https://" + text
    parsed = urllib.parse.urlparse(text)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        return None
    return f"{parsed.scheme}://{parsed.netloc}{parsed.path}".rstrip("/")


def main():
    parser = argparse.ArgumentParser(description="Código QR de configuración de Relaycast")
    parser.add_argument("--out", help="archivo PNG de salida (por defecto, en Descargas)")
    parser.add_argument("--no-open", action="store_true", help="no abrir la imagen al terminar")
    args = parser.parse_args()

    qrcode = ensure_qrcode()

    relay = None
    while relay is None:
        relay = canonical_relay(input("Dirección del relay (por ejemplo https://relay.midominio.com): "))
        if relay is None:
            print("  Esa dirección no es válida; prueba de nuevo.")

    print("Token administrador (no se muestra al escribir; déjalo vacío para un QR sólo con la dirección):")
    admin = getpass.getpass("> ").strip()
    if any(ch.isspace() for ch in admin) or len(admin) > 512:
        sys.exit("El token no es válido (no puede tener espacios).")

    link = "relaycast://provision?bridge=" + urllib.parse.quote(relay, safe="")
    if admin:
        link += "&admin=" + urllib.parse.quote(admin, safe="")

    out = Path(args.out) if args.out else Path.home() / "Downloads" / "configuracion-relaycast.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    image = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=12, border=4)
    image.add_data(link)
    image.make(fit=True)
    image.make_image(fill_color="black", back_color="white").save(out)

    print()
    print(f"Listo: {out}")
    print("Escanéalo desde Relaycast: Asistente de configuración inicial › Escanear código de configuración.")
    if admin:
        print("Ojo: con este QR cualquiera puede crear cuentas en tu relay. Compártelo sólo con quien confíes;")
        print("si se filtra, cambia el token administrador en el .env del servidor.")
    if not args.no_open and hasattr(os, "startfile"):
        os.startfile(out)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nCancelado.")
