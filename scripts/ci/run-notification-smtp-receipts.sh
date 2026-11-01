#!/usr/bin/env bash
# Real notification producers -> committed delivery claims -> authenticated STARTTLS -> receiver.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${script_dir}/../.."
maven_wrapper="${ORISO_MAVEN_WRAPPER:-./mvnw}"
task_dir="$(mktemp -d "${TMPDIR:-/tmp}/oriso-notification-smtp.XXXXXX")"
container_id=""
cleanup() {
  if [[ -n "${container_id}" ]]; then docker rm -f "${container_id}" >/dev/null 2>&1 || true; fi
  rm -rf "${task_dir}"
}
trap cleanup EXIT
openssl req -x509 -newkey rsa:2048 -nodes -days 2 \
  -keyout "${task_dir}/mailpit.key" -out "${task_dir}/mailpit.crt" \
  -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,DNS:mailpit' \
  >"${task_dir}/certificate.log" 2>&1
keytool -importcert -noprompt -alias notification-receiver \
  -file "${task_dir}/mailpit.crt" -keystore "${task_dir}/truststore.p12" \
  -storetype PKCS12 -storepass changeit >>"${task_dir}/certificate.log" 2>&1
# This is a disposable test key, accessible only under the owned temporary directory.
chmod 644 "${task_dir}/mailpit.key" "${task_dir}/mailpit.crt"
container_id="$(docker run -d --label oriso.test=notification-smtp \
  -e MP_SMTP_AUTH="synthetic-fixture:synthetic-fixture" -e MP_SMTP_REQUIRE_STARTTLS=true \
  -e MP_SMTP_TLS_CERT=/certs/mailpit.crt \
  -e MP_SMTP_TLS_KEY=/certs/mailpit.key \
  -p 127.0.0.1::1025 -p 127.0.0.1::8025 \
  --mount "type=bind,src=${task_dir},dst=/certs,readonly" \
  axllent/mailpit@sha256:74d609a42ec279aa63c6b4622a6fa9b5408d1ad5b1d76a1c4be40a265ce0863d)"
ORISO_SMTP_PORT="$(docker inspect --format '{{(index (index .NetworkSettings.Ports "1025/tcp") 0).HostPort}}' "${container_id}")"
api_port="$(docker inspect --format '{{(index (index .NetworkSettings.Ports "8025/tcp") 0).HostPort}}' "${container_id}")"
ORISO_MAILPIT_API="http://127.0.0.1:${api_port}"
export ORISO_SMTP_PORT ORISO_MAILPIT_API
python3 - <<'PY'
import os, time, urllib.request
for attempt in range(60):
    try:
        with urllib.request.urlopen(os.environ['ORISO_MAILPIT_API'] + '/api/v1/messages', timeout=1) as response:
            if response.status == 200:
                break
    except OSError:
        pass
    time.sleep(0.25)
else:
    raise SystemExit('The test-owned SMTP receiver did not become ready.')
PY
# Negative controls certify that receipts cannot bypass TLS or authentication.
python3 - "${task_dir}/mailpit.crt" <<'PYCONTROLS'
import os, smtplib, ssl, sys
port = int(os.environ['ORISO_SMTP_PORT'])
with smtplib.SMTP('localhost', port, timeout=5) as smtp:
    smtp.ehlo()
    code, _ = smtp.mail('negative@synthetic.oriso.test')
    if code < 400:
        raise SystemExit('The receiver accepted plaintext MAIL; STARTTLS is not enforced.')
with smtplib.SMTP('localhost', port, timeout=5) as smtp:
    smtp.starttls(context=ssl.create_default_context(cafile=sys.argv[1]))
    smtp.ehlo()
    code, _ = smtp.mail('negative@synthetic.oriso.test')
    if code < 400:
        raise SystemExit('The receiver accepted MAIL without authentication.')
print('Receiver negative controls: plaintext and unauthenticated MAIL refused.')
PYCONTROLS
report="target/surefire-reports/TEST-de.caritas.cob.userservice.api.service.notification.NotificationEmailSmtpContract.xml"
rm -f "${report}"
# A dedicated fork trusts only this fixture certificate; production hostname/TLS checks stay on.
"${maven_wrapper}" -B -s "${script_dir}/github-maven-settings.xml" \
  -Dtest=NotificationEmailSmtpContract -Dskip.unit-tests=false -DskipTests=false \
  "-DargLine=-Djavax.net.ssl.trustStore=\"${task_dir}/truststore.p12\" -Djavax.net.ssl.trustStorePassword=changeit -Djava.net.preferIPv4Stack=true" \
  test
python3 - "${report}" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
counts = {key: int(root.attrib.get(key, 0)) for key in ('tests', 'failures', 'errors', 'skipped')}
if counts['tests'] < 11 or any(counts[key] for key in ('failures', 'errors', 'skipped')):
    raise SystemExit(f'SMTP receipt contract did not qualify: {counts}')
print(f"Actual STARTTLS SMTP receipt contract: {counts['tests']} passed; no skipped cases.")
PY
