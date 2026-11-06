#!/usr/bin/env bash
# Turns on TLS in a running Postgres for clunk's TLS tests (test/clunk/tls_test.cljc).
#
#   PGHOST=localhost PGUSER=jimmy PGPASSWORD=banana PGDATABASE=world test/tls/configure-postgres.sh
#
# Makes, once, a test CA and the certificates signed by it in test/tls/certs
# (gitignored), then installs them in the server and reloads it:
#   ca.crt / ca.key          the CA the tests trust
#   server.crt / server.key  the server's, for DNS:localhost only, so 127.0.0.1 is a wrong hostname
#   client.crt / client.key  CN=clunk_cert, for certificate auth; the key is PKCS#8
#   other-ca.crt             a CA the server's certificate is not signed by
# and adds two users that may only connect over TLS:
#   clunk_ssl  (password clunk_ssl)  hostssl, scram-sha-256
#   clunk_cert                       hostssl, cert
#
# It needs openssl and psql here and a superuser in the PG* variables, nothing
# on the server's machine: it writes the server's files with COPY ... TO
# PROGRAM, so it works the same on a local cluster, a CI service container,
# or docker-compose. Running it again reinstalls the same certificates.
set -euo pipefail

certs="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/certs"

psql_su() { psql -v ON_ERROR_STOP=1 -qtAX "$@"; }

make_ca() { # name subject
  openssl req -x509 -new -newkey rsa:2048 -nodes -days 3650 -subj "/CN=$2" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -keyout "$1.key" -out "$1.crt" 2>/dev/null
}

make_cert() { # name subject extensions
  openssl req -new -newkey rsa:2048 -nodes -subj "/CN=$2" -keyout "$1.key" -out "$1.csr" 2>/dev/null
  openssl x509 -req -in "$1.csr" -CA ca.crt -CAkey ca.key -set_serial "0x$(openssl rand -hex 8)" \
    -days 3650 -extfile <(printf '%s\n' "$3") -out "$1.crt" 2>/dev/null
  rm "$1.csr"
}

make_certs() {
  local files=(ca.crt ca.key server.crt server.key client.crt client.key other-ca.crt)
  if (cd "$certs" 2>/dev/null && ls "${files[@]}" >/dev/null 2>&1); then
    echo "    certificates already in $certs"
    return
  fi
  mkdir -p "$certs"
  (
    cd "$certs"
    make_ca ca "clunk test CA"
    make_ca other-ca "clunk untrusted CA"
    rm other-ca.key
    make_cert server localhost "subjectAltName=DNS:localhost
extendedKeyUsage=serverAuth
basicConstraints=CA:FALSE"
    make_cert client clunk_cert "extendedKeyUsage=clientAuth
basicConstraints=CA:FALSE"
    chmod 600 ./*.key
  )
  echo "    made certificates in $certs"
}

# Runs a shell command on the server's machine, as the server's OS user, in its
# data directory, with file's bytes on stdin.
server_sh() { # file command
  local cmd="base64 -d | { $2; }"
  psql_su -c "COPY (SELECT '$(base64 -w0 <"$1")') TO PROGRAM '${cmd//\'/\'\'}'"
}

install_in_server() {
  # Relative paths in these settings are relative to the data directory.
  server_sh "$certs/server.crt" "cat > clunk-server.crt"
  server_sh "$certs/server.key" "umask 077 && cat > clunk-server.key"
  server_sh "$certs/ca.crt" "cat > clunk-ca.crt"

  # pg_hba.conf takes the first line that matches, so these go first. A plain
  # connection as either user is rejected rather than falling through to the
  # lines below. Any earlier copy of the block is replaced.
  local hba block
  hba="$(psql_su -c 'SHOW hba_file')"
  block="$(mktemp)"
  cat >"$block" <<'EOF'
# clunk-tls begin: added by test/tls/configure-postgres.sh
hostnossl all clunk_ssl,clunk_cert all reject
hostssl   all clunk_ssl            all scram-sha-256
hostssl   all clunk_cert           all cert
# clunk-tls end
EOF
  server_sh "$block" "{ cat; sed '/clunk-tls begin/,/clunk-tls end/d' $hba; } > $hba.clunk && cat $hba.clunk > $hba && rm $hba.clunk"
  rm "$block"

  psql_su <<'EOF'
SELECT 'CREATE ROLE clunk_ssl LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'clunk_ssl') \gexec
SELECT 'CREATE ROLE clunk_cert LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'clunk_cert') \gexec
ALTER ROLE clunk_ssl PASSWORD 'clunk_ssl';
ALTER SYSTEM SET ssl = on;
ALTER SYSTEM SET ssl_cert_file = 'clunk-server.crt';
ALTER SYSTEM SET ssl_key_file = 'clunk-server.key';
ALTER SYSTEM SET ssl_ca_file = 'clunk-ca.crt';
SELECT pg_reload_conf() \g /dev/null
EOF
}

check() {
  # The reload is asynchronous, so wait for a TLS connection the new CA verifies.
  local conn="sslmode=verify-ca sslrootcert=$certs/ca.crt user=clunk_ssl password=clunk_ssl dbname=${PGDATABASE:-postgres}"
  for _ in $(seq 1 20); do
    if [ "$(PGPASSWORD= psql -qtAX "$conn" -c 'SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()' 2>/dev/null)" = t ]; then
      echo "    clunk_ssl connects over TLS, verified by $certs/ca.crt"
      return
    fi
    sleep 0.5
  done
  PGPASSWORD= psql -qtAX "$conn" -c 'SELECT 1' # shows why it fails
  exit 1
}

make_certs
install_in_server
check
