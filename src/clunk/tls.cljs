(ns clunk.tls
  "Opens a client's connection on Node, over TLS when its config asks for it
   (see clunk.core/ssl-config for the settings).

   connect returns at once, as net.createConnection does, and holds writes
   until the connection is ready, so the byte-buffer socket uses it as it
   uses a net.Socket. With TLS, that is a Duplex in front of the real
   socket: once the server has answered SSLRequest, tls.connect({socket})
   upgrades the net.Socket in place, and the Duplex passes bytes to and from
   the TLS socket, or the plain one if :prefer falls back."
  (:require [clojure.string :as str]
            [clunk.messages :as messages]
            [clunk.vendor.binf :as binf]
            ["fs" :as fs]
            ["net" :as net]
            ["stream" :refer [Duplex]]
            ["tls" :as tls]))

(defn- pem
  "x as tls.connect takes a certificate or key: x itself if it is PEM text
   or a Buffer, else the file it names."
  [x]
  (if (and (string? x) (not (str/starts-with? (str/triml x) "-----BEGIN")))
    (fs/readFileSync x)
    x))

(defn- tls-options
  "Checks the server's certificate chain against :root-cert, or Node's
   bundled CAs when none is given, unless the mode is :prefer or :require
   without a :root-cert. Only :verify-full checks that it names the server."
  [{:keys [mode servername root-cert cert key options]}]
  (js/Object.assign
   #js {:host                servername ;; with :socket, only for the name check
        ;; Node won't send an IP address for SNI.
        :servername          (when-not (pos? (net/isIP servername)) servername)
        ;; Required for direct negotiation; harmless otherwise. libpq sends it too.
        :ALPNProtocols       #js ["postgresql"]
        :rejectUnauthorized  (boolean (or (#{:verify-ca :verify-full} mode) root-cert))
        :checkServerIdentity (if (= :verify-full mode)
                               tls/checkServerIdentity
                               (fn [_ _] js/undefined))
        :ca                  (some-> root-cert pem)
        :cert                (some-> cert pem)
        :key                 (some-> key pem)}
   options))

(defn- write-through
  "Writes chunk to s, calling cb once s can take more."
  [^js s chunk cb]
  (if (.write s chunk) (cb) (.once s "drain" (fn [] (cb)))))

(defn- negotiate
  "A Duplex for a connection that asks for TLS before Postgres hears from
   the client, or with direct negotiation starts it at once."
  [port host {:keys [mode negotiation] :as ssl}]
  (let [raw    (net/createConnection port host)
        inner  (volatile! nil) ;; the socket that carries Postgres's bytes, once ready
        held   (volatile! nil) ;; [chunk cb] written before then
        conn   (Duplex. #js {:write   (fn [chunk _ cb]
                                        (if-let [s @inner] (write-through s chunk cb) (vreset! held [chunk cb])))
                             :read    (fn [] (some-> ^js @inner .resume))
                             :final   (fn [cb] (if-let [^js s @inner] (.end s) (.destroy raw)) (cb))
                             :destroy (fn [err cb] (some-> ^js @inner .destroy) (.destroy raw) (cb err))})
        fail!  (fn [msg cause] (.destroy conn (js/Error. msg #js {:cause cause})))
        ready! (fn [^js s]
                 (vreset! inner s)
                 (.on s "data" (fn [d] (when-not (.push conn d) (.pause s))))
                 (.on s "end" (fn [] (.push conn nil)))
                 (.on s "close" (fn [] (.destroy conn)))
                 (when-let [[chunk cb] @held]
                   (vreset! held nil)
                   (write-through s chunk cb)))
        start! (fn []
                 (let [t (tls/connect (js/Object.assign (tls-options ssl) #js {:socket raw}))]
                   (.on t "error" (fn [e] (fail! (str (when-not @inner "TLS handshake failed: ") (.-message e)) e)))
                   (.once t "secureConnect" (fn [] (ready! t)))))]
    (.on raw "error" (fn [e] (.destroy conn e)))
    (if (= :direct negotiation)
      (.once raw "connect" start!)
      (do (.once raw "connect" (fn [] (.write raw (js/Uint8Array. (binf/backing-buffer (messages/build-ssl-request))))))
          (.once raw "data"
                 (fn [^js d]
                   (let [answer (js/String.fromCharCode (aget d 0))]
                     (cond
                       ;; Bytes after the answer came before TLS, so anyone could have sent them.
                       (< 1 (.-length d)) (fail! "The server sent unencrypted data after its answer to SSLRequest" nil)
                       (= "S" answer)     (start!)
                       (and (= "N" answer) (= :prefer mode)) (ready! raw)
                       (= "N" answer)     (fail! (str "The server does not support TLS, which :ssl " mode " needs") nil)
                       :else              (fail! (str "The server answered SSLRequest with " (pr-str answer)) nil)))))))
    conn))

(defn connect
  "Connects to host:port and returns at once a socket, or socket-like Duplex,
   to talk to Postgres on. ssl is nil for a plain connection, or a map from
   clunk.core/ssl-config. Writes wait until the connection is ready for the
   StartupMessage. If TLS fails, or the server won't talk TLS and the mode
   needs it, it is destroyed with an error that says why."
  [port host ssl]
  (if ssl
    (negotiate port host ssl)
    (net/createConnection port host)))
