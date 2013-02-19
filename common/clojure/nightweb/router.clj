(ns nightweb.router
  (:use [nightweb.crypto :only [create-user-keys create-signature]]
        [nightweb.io :only [file-exists?
                            make-dir
                            iterate-dir
                            base32-encode
                            base32-decode
                            write-priv-node-key-file
                            write-pub-node-key-file
                            write-link-file]]
        [nightweb.constants :only [slash
                                   torrent-ext
                                   pub-key
                                   users-dir
                                   get-meta-dir
                                   get-user-dir]]
        [nightweb.torrent :only [start-torrent-manager
                                 iterate-peers-per-torrent
                                 add-hash
                                 add-torrent
                                 remove-torrent]]
        [nightweb.torrent-dht :only [get-public-node
                                     get-private-node
                                     add-node
                                     send-meta-link
                                     set-dht-node-keys-from-disk
                                     set-dht-custom-query-handler]]))

(def base-dir nil)
(def user-hash-bytes nil)
(def user-hash-str nil)

(defn get-user-hash
  [is-binary?]
  (if is-binary? user-hash-bytes user-hash-str))

(defn add-user-hash
  [their-hash-bytes]
  (let [their-hash-str (base32-encode their-hash-bytes)
        path (str base-dir (get-user-dir their-hash-str))]
    (when-not (file-exists? path)
      (make-dir path)
      (add-hash path their-hash-str true))))

(defn add-user-torrents
  []
  (iterate-dir (str base-dir users-dir)
               (fn [dir-name]
                 (let [user-dir (str base-dir (get-user-dir dir-name))
                       pub-path (str user-dir slash pub-key)
                       pub-torrent-path (str pub-path torrent-ext)
                       meta-path (str base-dir (get-meta-dir dir-name))
                       meta-torrent-path (str meta-path torrent-ext)]
                   (if (not= dir-name (get-user-hash false))
                     (if (file-exists? pub-torrent-path)
                       (add-torrent pub-path true)
                       (add-hash user-dir dir-name true)))
                   (if (file-exists? meta-torrent-path)
                     (add-torrent meta-path false))))))

(defn dht-init-callback
  []
  (java.lang.Thread/sleep 3000)
  (let [priv-node (get-private-node)
        pub-node (get-public-node)]
    (when (and priv-node pub-node)
      (write-priv-node-key-file base-dir priv-node)
      (write-pub-node-key-file base-dir pub-node)))
  (add-node "rkJ0ws6PQz8FU7VvTW~Lelhb6DM=:rkJ0wkX6jrW3HJBNdhuLlWCUPKDAlX8T23lrTOeMGK8=:B5QFqHHlCT5fOA2QWLAlAKba1hIjW-KBt2HCqwtJg8JFa2KnjAzcexyveYT8HOcMB~W6nhwhzQ7~sywFkvcvRkKHbf6LqP0X43q9y2ADFk2t9LpUle-L-x34ZodEEDxQbwWo74f-rX5IemW2-Du-8NH-o124OGvq5N4uT4PjtxmgSVrBYVLjZRYFUWgdmgR1lVOncfMDbXzXGf~HdY97s9ZFHYyi7ymwzlk4bBN9-Pd4I1tJB2sYBzk62s3gzY1TlDKOdy7qy1Eyr4SEISAopJrvAnSkS1eIFyCoqfzzrBWM11uWppWetf3AkHxGitJIQe73wmZrrO36jHNewIct54v2iF~~3cqBVlT4ptX1Dc-thjrxXoV73A0HUASldCeFZSVJFMQgOQK9U85NQscAokftpyp4Ai89YWaUvSDcZPd-mQuA275zifPwp8s8UfYV5EBqvdHnfeJjxmyTcKR3g5Ft8ABai9yywxoA7yoABD4EGzsFtAh0nOLcmbM944zdAAAA:35701"))

(defn create-user-torrent
  []
  (let [pub-key-path (create-user-keys base-dir)]
    (add-torrent pub-key-path true dht-init-callback)))

(defn create-meta-torrent
  []
  (let [path (str base-dir (get-meta-dir (get-user-hash false)))
        _ (remove-torrent path)
        link-hash-bytes (add-torrent path false)]
    (write-link-file path
                     user-hash-bytes
                     link-hash-bytes
                     (fn [data] (create-signature data)))))

(defn parse-url
  [url]
  (let [url-str (subs url (+ 1 (.indexOf url "#")))
        url-vec (clojure.string/split url-str #"[&=]")
        url-map (if (even? (count url-vec))
                  (apply hash-map url-vec)
                  {})
        {type-val "type" hash-val "hash"} url-map]
    {:type (if type-val (keyword type-val) nil)
     :hash (if hash-val (base32-decode hash-val) nil)}))

(defn start-router
  [dir]
  (def base-dir dir)
  (java.lang.System/setProperty "i2p.dir.base" dir)
  (java.lang.System/setProperty "i2p.dir.config" dir)
  (java.lang.System/setProperty "wrapper.logfile" (str dir slash "wrapper.log"))
  (net.i2p.router.RouterLaunch/main nil)
  (start-torrent-manager dir)
  (set-dht-node-keys-from-disk dir)
  (set-dht-custom-query-handler dir)
  (java.lang.Thread/sleep 3000)
  (def user-hash-bytes (create-user-torrent))
  (def user-hash-str (base32-encode user-hash-bytes))
  (add-user-torrents)
  (future
    (while true
      (java.lang.Thread/sleep 30000)
      (iterate-peers-per-torrent
        (fn [path node-info]
          (send-meta-link path node-info dir (get-user-hash false)))))))

(defn stop-router
  []
  (if-let [contexts (net.i2p.router.RouterContext/listContexts)]
    (if-not (.isEmpty contexts)
      (if-let [context (.get contexts 0)]
        (.shutdown (.router context) net.i2p.router.Router/EXIT_HARD)))))
