(ns clunk.tls
  "Opens a client's connection on the JVM, over TLS when its config asks for
   it (see clunk.core/ssl-config for the settings).

   TLS here is JSSE's SSLSocket layered over the connected SocketChannel's
   socket, with blocking streams. connect hands back a ByteChannel either
   way, so the connection's reader thread reads it, and whoever starts a
   query writes it, as they would a SocketChannel; SSLSocket lets one thread
   read while another writes. A non-blocking (selector) design would need
   SSLEngine instead; a prototype of it benchmarked the same."
  (:require [clojure.string :as str]
            [clunk.messages :as messages]
            [clunk.vendor.binf :as binf])
  (:import (java.io ByteArrayInputStream IOException InputStream OutputStream)
           (java.net InetSocketAddress StandardSocketOptions)
           (java.nio ByteBuffer)
           (java.nio.channels ByteChannel SocketChannel)
           (java.security KeyFactory KeyStore)
           (java.security.cert Certificate CertificateFactory X509Certificate)
           (java.security.spec PKCS8EncodedKeySpec)
           (java.util Base64)
           (javax.net.ssl KeyManagerFactory SNIHostName SSLContext
                          SSLException SSLParameters SSLSocket TrustManager TrustManagerFactory
                          X509ExtendedTrustManager)))

(defn- pem
  "x as PEM text: x itself if it is PEM, else the file it names."
  ^String [x]
  (if (str/starts-with? (str/triml x) "-----BEGIN") x (slurp x)))

(defn- certificates [x]
  (.generateCertificates (CertificateFactory/getInstance "X.509")
                         (ByteArrayInputStream. (.getBytes (pem x) "US-ASCII"))))

(defn- private-key
  "An unencrypted PKCS#8 key (BEGIN PRIVATE KEY), RSA or EC, as OpenSSL 3
   writes them. Java reads no other PEM key format."
  [x]
  (let [[_ kind body] (re-find #"-----BEGIN ([A-Z ]*)PRIVATE KEY-----([^-]*)-----END" (pem x))]
    (when-not (= "" kind)
      (throw (ex-info (str ":ssl-key must be an unencrypted PKCS#8 key (BEGIN PRIVATE KEY), not "
                           (if kind (str "BEGIN " kind "PRIVATE KEY") "this")
                           "; convert it with openssl pkcs8 -topk8 -nocrypt")
                      {})))
    (let [spec (PKCS8EncodedKeySpec. (.decode (Base64/getMimeDecoder) (str/trim body)))]
      (or (some #(try (.generatePrivate (KeyFactory/getInstance %) spec) (catch Exception _ nil))
                ["RSA" "EC"])
          (throw (ex-info ":ssl-key is neither an RSA nor an EC key" {}))))))

(def ^:private trust-all
  "For :prefer and :require without a root certificate: encrypt, but take
   any certificate, as libpq does."
  (proxy [X509ExtendedTrustManager] []
    (checkClientTrusted [_ _ & _])
    (checkServerTrusted [_ _ & _])
    (getAcceptedIssuers [] (make-array X509Certificate 0))))

(defn- trust-managers [root-cert]
  (let [ks (doto (KeyStore/getInstance (KeyStore/getDefaultType)) (.load nil nil))]
    (doseq [[i c] (map-indexed vector (certificates root-cert))]
      (.setCertificateEntry ks (str "ca-" i) c))
    (.getTrustManagers (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
                         (.init ks)))))

(defn- key-managers [cert key]
  (let [pw (.toCharArray "clunk")
        ks (doto (KeyStore/getInstance "PKCS12") (.load nil nil))]
    (.setKeyEntry ks "client" (private-key key) pw (into-array Certificate (certificates cert)))
    (.getKeyManagers (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
                       (.init ks pw)))))

(defn- ssl-context
  "Checks the server's certificate chain against :root-cert, or the JVM's
   trust store when none is given, unless the mode is :prefer or :require
   without a :root-cert. Whether the name matches is checked separately."
  ^SSLContext [{:keys [mode root-cert cert key context]}]
  (or context
      (doto (SSLContext/getInstance "TLS")
        (.init (when cert (key-managers cert key))
               (cond root-cert                               (trust-managers root-cert)
                     (#{:verify-ca :verify-full} mode)       nil ;; the JVM's default
                     :else                                   (into-array TrustManager [trust-all]))
               nil))))

(defn- ip-address? [host]
  (boolean (or (re-matches #"\d{1,3}(\.\d{1,3}){3}" host) (str/includes? host ":"))))

(defn- ssl-request!
  "Sends SSLRequest and returns the server's answer, a char. Reads exactly one
   byte, so anything a server or a man in the middle sends after it, still in
   plain text, is left for the TLS handshake to reject."
  [^SocketChannel ch]
  (let [req  (ByteBuffer/wrap (binf/backing-buffer (messages/build-ssl-request)))
        resp (ByteBuffer/allocate 1)]
    (while (.hasRemaining req) (.write ch req))
    (while (and (.hasRemaining resp) (not (neg? (.read ch resp)))))
    (when (.hasRemaining resp)
      (throw (ex-info "The server closed the connection in reply to SSLRequest" {})))
    (char (.get resp 0))))

(defn- tls-parameters
  ^SSLParameters [^SSLParameters params {:keys [mode ^String servername]}]
  ;; Required for direct negotiation; harmless otherwise. libpq sends it too.
  (.setApplicationProtocols params (into-array String ["postgresql"]))
  (when (= :verify-full mode)
    (.setEndpointIdentificationAlgorithm params "HTTPS")) ;; RFC 2818 name checks
  (when-not (ip-address? servername)
    (.setServerNames params [(SNIHostName. servername)]))
  params)

(defn- start-tls
  "Layers TLS over ch's socket and finishes the handshake, so a rejected
   certificate fails here."
  ^SSLSocket [^SocketChannel ch port {:keys [mode ^String servername] :as ssl}]
  (let [sock ^SSLSocket (.createSocket (.getSocketFactory (ssl-context ssl)) (.socket ch) servername (int port) true)]
    (.setSSLParameters sock (tls-parameters (.getSSLParameters sock) ssl))
    (try (.startHandshake sock)
         sock
         (catch IOException e
           (.close sock)
           (throw (ex-info (str "TLS handshake failed: " (ex-message e)) {:ssl mode} e))))))

(defn- stream-channel
  "The SSLSocket as the ByteChannel the byte-buffer socket reads and writes.
   Both of its buffers are heap ByteBuffers."
  ^ByteChannel [^SSLSocket sock]
  (let [^InputStream in   (.getInputStream sock)
        ^OutputStream out (.getOutputStream sock)]
    (reify ByteChannel
      (read [_ bb]
        (let [^ByteBuffer bb bb
              n (.read in (.array bb) (+ (.arrayOffset bb) (.position bb)) (.remaining bb))]
          (when (pos? n) (.position bb (+ (.position bb) n)))
          n))
      (write [_ bb]
        (let [^ByteBuffer bb bb
              n (.remaining bb)]
          (.write out (.array bb) (+ (.arrayOffset bb) (.position bb)) n)
          (.position bb (.limit bb))
          n))
      (isOpen [_] (not (.isClosed sock)))
      (close [_] (.close sock)))))

(defn connect
  "Connects to host:port and returns a ByteChannel to talk to Postgres on,
   ready for the StartupMessage. ssl is nil for a plain connection, or a map
   from clunk.core/ssl-config, in which case it asks for TLS (or with direct
   negotiation, starts it at once) and blocks until the handshake is done.
   Throws if TLS fails or the server won't talk TLS and the mode needs it."
  ^ByteChannel [port ^String host {:keys [mode negotiation] :as ssl}]
  (let [ch (SocketChannel/open (InetSocketAddress. host (int port)))]
    (try
      ;; As libpq and pgjdbc do. Without it, a message written right after
      ;; another (the StartupMessage after the TLS handshake's last flight)
      ;; waits for the server's delayed ACK, about 40 ms.
      (.setOption ch StandardSocketOptions/TCP_NODELAY true)
      (if-not ssl
        ch
        (let [answer (if (= :direct negotiation) \S (ssl-request! ch))]
          (case answer
            \S (stream-channel (start-tls ch port ssl))
            \N (if (= :prefer mode)
                 ch
                 (throw (ex-info (str "The server does not support TLS, which :ssl " mode " needs") {:ssl mode})))
            (throw (ex-info (str "The server answered SSLRequest with " (pr-str answer)) {:ssl mode})))))
      (catch Throwable e
        (.close ch)
        (throw e)))))
