; Lets phones and TVs on the home network reach the server: an inbound rule
; for the app (the server runs inside the same executable), private networks only.
!macro customInstall
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Relay Server"'
  nsExec::Exec 'netsh advfirewall firewall add rule name="Relay Server" dir=in action=allow program="$INSTDIR\${APP_EXECUTABLE_FILENAME}" enable=yes profile=private'
!macroend

!macro customUnInstall
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Relay Server"'
!macroend
