(ns io.rrudakov.timbre.syslog
  (:require
   [clojure.string :as str])
  (:import
   (java.io IOException PrintWriter StringWriter)
   (java.net DatagramPacket DatagramSocket InetAddress InetSocketAddress)
   (java.time LocalDateTime ZoneId)
   (java.time.format DateTimeFormatter)))

(set! *warn-on-reflection* true)

(def ^:private syslog-udp-port 514)
(def ^:private syslog-ts-formatter (DateTimeFormatter/ofPattern "LLL dd HH:mm:ss"))

(def ^:private level->syslog-priority
  "Map timbre log levels to syslog levels.

  Syslog levels:
  - LOG_EMERG     = 0 -> system is unusable
  - LOG_ALERT     = 1 -> action must be taken immediately
  - LOG_CRIT      = 2 -> critical conditions
  - LOG_ERR       = 3 -> error conditions
  - LOG_WARNING   = 4 -> warning conditions
  - LOG_NOTICE    = 5 -> normal but significant condition
  - LOG_INFO      = 6 -> informational
  - LOG_DEBUG     = 7 -> debug-level messages"
  {:trace  7
   :debug  7
   :info   6
   :warn   4
   :error  3
   :report 1
   :fatal  0})

(def ^:private facility-map
  "Map keywords to syslog facility codes.

  Syslog facility codes:
  - LOG_KERN      = 0  -> kernel messages
  - LOG_USER      = 1  -> random user-level messages
  - LOG_MAIL      = 2  -> mail system
  - LOG_DAEMON    = 3  -> system daemons
  - LOG_AUTH      = 4  -> security/authorization messages
  - LOG_SYSLOG    = 5  -> messages generated internally by syslogd
  - LOG_LPR       = 6  -> line printer subsystem
  - LOG_NEWS      = 7  -> network news subsystem
  - LOG_UUCP      = 8  -> UUCP subsystem
  - LOG_CRON      = 9  -> clock daemon
  - LOG_AUTHPRIV  = 10 -> security/authorization messages (private)
  - LOG_FTP       = 11 -> FTP daemon
  - LOG_NTP       = 12 -> NTP subsystem
  - LOG_SECURITY  = 13 -> Log audit
  - LOG_CONSOLE   = 14 -> Log alert
  - LOG_SOLCRON   = 15 -> Scheduling daemon (Solaris)

  Other codes through 15 reserved for system use:
  - LOG_LOCAL0    = 16 -> reserved for local use
  - LOG_LOCAL1    = 17 -> reserved for local use
  - LOG_LOCAL2    = 18 -> reserved for local use
  - LOG_LOCAL3    = 19 -> reserved for local use
  - LOG_LOCAL4    = 20 -> reserved for local use
  - LOG_LOCAL5    = 21 -> reserved for local use
  - LOG_LOCAL6    = 22 -> reserved for local use
  - LOG_LOCAL7    = 23 -> reserved for local use"
  {:log-kern     0
   :log-user     1
   :log-mail     2
   :log-daemon   3
   :log-auth     4
   :log-syslog   5
   :log-lpr      6
   :log-news     7
   :log-uucp     8
   :log-cron     9
   :log-authpriv 10
   :log-ftp      11
   :log-ntp      12
   :log-security 13
   :log-local0   16
   :log-local1   17
   :log-local2   18
   :log-local3   19
   :log-local4   20
   :log-local5   21
   :log-local6   22
   :log-local7   23})

(defn- format-ts
  [^java.util.Date ts]
  (let [local-date-time (-> ts
                            (.toInstant)
                            (.atZone (ZoneId/systemDefault))
                            (.toLocalDateTime))]
    (.format ^DateTimeFormatter syslog-ts-formatter
             ^LocalDateTime local-date-time)))

(defn- socket-connect ^DatagramSocket
  [inet-socket-address]
  (doto (DatagramSocket.)
    (.connect inet-socket-address)))

(defn- encode-priority
  [facility level]
  (bit-or (bit-shift-left (facility facility-map) 3)
          (level level->syslog-priority)))

(defn- send-datagram-packet
  [socket
   ^InetAddress inet-address
   ^Integer port
   ^String prefix
   ^String message]
  (try
    (let [message-bytes (->> [prefix (.getBytes message) [(byte 0)]]
                             (into [] (comp cat (take 1024)))
                             (byte-array))
          message-size  (count message-bytes)
          packet        (DatagramPacket. message-bytes
                                         message-size
                                         inet-address
                                         port)]
      (.send ^DatagramSocket @socket packet))
    (catch IOException _
      (let [inet-socket-address (InetSocketAddress. inet-address port)]
        (reset! socket (socket-connect inet-socket-address))
        (send-datagram-packet socket inet-address port prefix message)))))

(defn- log-message
  [socket inet-address port facility {:keys [level output_]}]
  (let [prefix  (.getBytes (format "<%d>" (encode-priority facility level)))
        message (force output_)]
    (doseq [line (str/split-lines message)]
      (send-datagram-packet socket inet-address port prefix line))))

(defn- format-message
  [ident
   {:keys [no-stacktrace?]}
   {:keys [msg_ ?ns-str ?file ?line ?err instant hostname_]}]
  (let [prefix (str
                (when instant (str (format-ts instant) " "))
                (force hostname_)  " "
                (when ident (str ident " ")))]
    (str
     prefix
     "[" (or ?ns-str ?file "?") ":" (or ?line "?") "] - "
     (force msg_)
     (when-not no-stacktrace?
       (when-let [^Exception err ?err]
         (let [sw          (StringWriter.)
               pw          (PrintWriter. sw)
               _           (.printStackTrace err pw)
               st-lines    (str/split-lines (.toString sw))
               st-prefixed (into [] (map #(str prefix %)) st-lines)]
           (str "\n" (str/join "\n" st-prefixed))))))))

(defn syslog-appender
  "Return new timbre appender which publish messages to remote syslog.

  The function accepts a single map with the following keys:
  - `:host` - remote host to connect (default: `localhost`)
  - `:port` - UDP port (default: `514`)
  - `:ident` - application identity, part of RFC 3164 (default: `nil`)
  - `:no-stacktrace?` - whether publish or not stacktraces (default: `true`)

  It's not recommended to modify the `:output-fn`. "
  [{:keys [host port facility ident no-stacktrace?]
    :or   {facility       :log-user
           host           "localhost"
           port           syslog-udp-port
           no-stacktrace? true}}]
  (let [inet-address        (InetAddress/getByName host)
        inet-socket-address (InetSocketAddress. ^InetAddress inet-address
                                                ^int port)
        socket              (atom (socket-connect inet-socket-address))
        ident-str           (when ident
                              (if (str/ends-with? (str/trim ident) ":")
                                (apply str (take 32 (str/trim ident)))
                                (apply str (concat (take 32 (str/trim ident)) [\:]))))]
    {:enabled?  true
     :async?    true
     :min-level nil
     :output-fn (partial format-message
                         ident-str
                         {:no-stacktrace? no-stacktrace?})
     :fn
     (fn [data]
       (log-message socket
                    inet-address
                    port
                    facility
                    data))}))
