(ns asynp.core
  (:require [clojure.core.async :as async :refer [<! >! <!! >!! timeout chan alts! alt!! alts!! go close!]]
            [taoensso.timbre :as timbre])
  (:import [com.zaxxer.nuprocess NuProcess NuProcessBuilder NuProcessHandler]
           [java.nio ByteBuffer CharBuffer]
           [java.nio.charset Charset CoderResult]
           [java.util.regex Pattern]))

;; core.async makes avoiding reflection usually difficult -- hints inside go
;; blocks are frequently ineffective.
(set! *warn-on-reflection* true)

(def ^:dynamic *working-buffer-size* 1024)

(defn array-from-buffer [^ByteBuffer buffer]
  (let [byte-count (.remaining buffer)
        dest (byte-array byte-count)]
    (.get buffer dest)
    dest))

(defn run-process [argv]
  (let [in-chan (chan), out-chan (chan), err-chan (chan), exit-chan (chan 1)
        process-atom (atom nil)]
    (let [^NuProcessHandler handler
          (proxy [NuProcessHandler] []
            (onStart [process]
              (timbre/trace "onStart called for" process)
              (reset! process-atom process))
            (onExit [statusCode]
              (timbre/trace "onExit called with" statusCode)
              (>!! exit-chan statusCode)
              (close! out-chan)
              (close! err-chan)
              (close! exit-chan))
            (onStdout [^ByteBuffer buffer]
              (timbre/trace "onStdout called with" buffer)
              (when buffer
                (>!! out-chan buffer)))
            (onStderr [^ByteBuffer buffer]
              (timbre/trace "onStderr called with" buffer)
              (when buffer
                (>!! err-chan buffer)))
            (onStdinReady [^ByteBuffer buffer]
              (timbre/trace "Performing deferred close")
              (let [^NuProcess process @process-atom]
                (.closeStdin process))
              false))
          ^java.util.List argv-list (apply list argv)
          builder (NuProcessBuilder. handler argv-list)]
      (let [process (.start builder)]
        (go
          (loop []
            (let [^bytes content (<! in-chan)]
              (if content
                (do
                  (timbre/trace "Writing" (alength ^bytes content) "bytes to process")
                  (.writeStdin process (ByteBuffer/wrap content))
                  (recur))
                (do
                  (timbre/trace "stdin stream ended; telling NuProcess to callback after flush")
                  (.wantWrite process))))))
        {:process process
         :in in-chan
         :out out-chan
         :err err-chan
         :exit exit-chan}))))

(defn decode-chars
  "given a stream of ByteBuffers, emit a stream of CharBuffers"
  ([in-chan]
     (decode-chars in-chan (Charset/forName "utf8")))
  ([in-chan, ^Charset charset]
     (let [out-chan (chan)
           decoder (.newDecoder charset)]
       (go
         (loop [^ByteBuffer working-buffer (ByteBuffer/allocate *working-buffer-size*) ; compacted and ready to receive writes
                ^ByteBuffer in-buffer (<! in-chan)]
           (timbre/trace "decoder: starting loop with working buffer " working-buffer " processing content " in-buffer)
           (cond
            (nil? in-buffer)
            (do
              (.flip ^ByteBuffer working-buffer) ; use as a source
              (timbre/trace "decoder: end of input stream seen; flushing the rest of " working-buffer)
              (let [out-buffer (CharBuffer/allocate (.remaining ^ByteBuffer working-buffer))
                    decode-result (.decode decoder ^ByteBuffer working-buffer ^CharBuffer out-buffer true)]

                (.flush decoder ^CharBuffer out-buffer)
                (.flip out-buffer)
                (when (> (.remaining out-buffer) 0)
                  (>! out-chan out-buffer))
                (close! out-chan))
              nil)

            (< (.remaining ^ByteBuffer working-buffer) (.remaining ^ByteBuffer in-buffer))
            (do
              (timbre/trace "decoder: resizing working buffer (" (.remaining ^ByteBuffer working-buffer)
                            " bytes left of " (.remaining ^ByteBuffer in-buffer) "needed")
              (let [new-working-buffer (ByteBuffer/allocate (+ *working-buffer-size*
                                                               (.capacity ^ByteBuffer working-buffer)
                                                               (.remaining ^ByteBuffer in-buffer)))]
                (.flip ^ByteBuffer working-buffer) ; use as a source for copy
                (.put ^ByteBuffer new-working-buffer ^ByteBuffer working-buffer)
                (recur new-working-buffer in-buffer)))

            :else
            (do
              (timbre/trace "decoder: running a regular cycle; in-buffer " in-buffer ", working-buffer " working-buffer)
              (.put ^ByteBuffer working-buffer ^ByteBuffer in-buffer)
              (.flip ^ByteBuffer working-buffer) ; use a source for decoding
              (timbre/trace "Trying to decode working-buffer: " working-buffer)
              (let [out-buffer (CharBuffer/allocate (.remaining ^ByteBuffer working-buffer))
                    decode-result (.decode decoder ^ByteBuffer working-buffer ^CharBuffer out-buffer false)]
                (timbre/trace "Decoding into" out-buffer
                              "of size" (.capacity ^CharBuffer out-buffer)
                              "resulted in" decode-result
                              "with" (.position out-buffer) "characters decoded")
                (cond
                 (or (= decode-result CoderResult/UNDERFLOW)
                     (= decode-result CoderResult/OVERFLOW))
                 (do
                   (.compact ^ByteBuffer working-buffer) ;; leave working-buffer ready to receive writes
                   (.flip ^CharBuffer out-buffer)        ;; leave output buffer ready to for reads
                   (>! out-chan out-buffer)              ;; write decoded content to channel
                   (recur working-buffer (<! in-chan)))  ;; read more content to decode

                 (.isError ^CoderResult decode-result)
                 (do
                   (try
                     (.throwException ^CoderResult decode-result)
                     (catch Exception e
                       (timbre/error e "Ending decode due to error")))
                   (close! out-chan)
                   nil)))))))
       out-chan)))

(defn split-by-char [in-chan delim-char]
  "given a channel delivering character arrays, merge and split by a delimiter"
  (let [out-chan (chan)
        pattern (Pattern/compile (str delim-char) Pattern/LITERAL)]
    (go
      (loop [strings-without-delim []
             input-charbuf (<! in-chan)]
        (timbre/trace "Buffered:" (pr-str strings-without-delim))
        (timbre/trace "Handling:" (pr-str input-charbuf))
        (if input-charbuf
          (let [input-str (.toString ^CharBuffer input-charbuf)
                pieces (vec (.split pattern input-str -1))]
            (timbre/trace "Split string" (pr-str input-str)
                          "into pieces" (pr-str pieces)
                          "on delimiter" (pr-str delim-char))
            (if (= (count pieces) 1)
              (do
                ;; single piece is appended to queue
                (recur (conj strings-without-delim input-str) (<! in-chan)))
              (do
                ;; first piece is appended to queued strings
                (>! out-chan (apply str (conj strings-without-delim (first pieces))))
                ;; middle pieces go out as-is
                (doseq [ready-string (rest (pop pieces))]
                  (>! out-chan ready-string))
                ;; last piece is queued for later
                (let [last-piece (last pieces)]
                  (recur [last-piece] (<! in-chan))))))
          (do
            ;; end of stream
            (let [last-piece (apply str strings-without-delim)]
              (when-not (empty? last-piece)
                (>! out-chan )))))))
    out-chan))

(defn log-strings
  ([in-chan]
     "Debugging aid: Given a channel delivering character buffers, log the contained strings using Timbre"
     (go
       (loop [char-buffer-in (<! in-chan)]
         (when char-buffer-in
           (timbre/trace "log-strings received buffer: " char-buffer-in)
           (let [s (.toString ^CharBuffer char-buffer-in)]
             (timbre/info "Read string: " s))
           (recur (<! in-chan))))))
  ([in-chan split-char]
     "Debugging aid: Given a channel delivering character buffers, split strings by a given character and log them individually using Timbre"
     (let [str-chan (split-by-char in-chan split-char)]
       (go (loop [string-in (<! str-chan)]
             (timbre/info "Read string: " string-in)
             (recur (<! str-chan)))))))


(defn write-str-to-process [proc-dict, ^String s]
  (>!! (:in proc-dict) (.getBytes s)))

(defn close-stdin-for-process [proc-dict]
  "Queue a close event for a process's stdin.

  This event will only be executed after the process is ready to read from its stdin"
  (close! (:in proc-dict)))

(defn wait-for-process
  ([proc-dict]
     "Block until a process exits"
     (<!! (:exit proc-dict)))
  ([proc-dict timeout-ms]
     "Wait for up to timeout-ms for process to exit; return exit status if it does, nil if it does not"
     (let [exit-chan {:exit proc-dict}
           [c v] (alts!! [exit-chan (timeout timeout-ms)])]
       (when (= c exit-chan)
         v))))
