(ns bombvoyage.gui
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [bombvoyage.game :as g]
            [clojure.set :as s]
            [cljs.reader :refer [read-string]]
            [goog.dom :as dom]
            [goog.events :as events]
            [chord.client :refer [ws-ch]]
            [cljs.core.async :refer [<! >! put! chan timeout]]
            [reagent.core :as r]))

(enable-console-print!)

(def MY-ID 21)
(def game-state
  (r/atom
    (-> (g/rand-game)
        (g/add-player 73)
        (g/add-player 21))))

(defn render-wood [[x y]] ^{:key (str [x y])}
  [:rect {:x (* g/TILE-SIZE x)
          :y (* g/TILE-SIZE y)
          :width g/TILE-SIZE
          :height g/TILE-SIZE
          :fill "#ffff9e"}])

(defn render-stone [[x y]] ^{:key (str [x y])}
  [:rect {:x (* g/TILE-SIZE x)
          :y (* g/TILE-SIZE y)
          :width g/TILE-SIZE
          :height g/TILE-SIZE
          :fill "#c4c4c4"}])

(defn render-player [{[x y] :pos id :id}] ^{:key (str [id x y])}
  [:image {:x x
           :y y
           :width g/TILE-SIZE
           :height g/TILE-SIZE
           :xlinkHref "/monkey_sprite.png"}])

(defn render-length-up [[x y]] ^{:key (str [x y])}
  [:image {:x (* g/TILE-SIZE x)
           :y (* g/TILE-SIZE y)
           :width g/TILE-SIZE
           :height g/TILE-SIZE
           :xlinkHref "/star.png"}])

(defn render-bomb-up [[x y]] ^{:key (str [x y])}
  [:image {:x (* g/TILE-SIZE x)
           :y (* g/TILE-SIZE y)
           :width g/TILE-SIZE
           :height g/TILE-SIZE
           :xlinkHref "/star2.png"}])

(defn center [x] (-> x (* g/TILE-SIZE) (+ (/ g/TILE-SIZE 2))))

(defn render-bomb [{:keys [pos ticks-left]}]
  (let [[x y] (map center pos)
        col (+ 40 (* 2 ticks-left))] ^{:key (str pos)}
    [:circle {:cx x
              :cy y
              :fill (str "rgb(255, " col ", " col ")")
              :r 16}]))

(defn render-explosion [{:keys [pos ticks-left dir]}]
  (let [[x y] pos] ^{:key (str pos dir)}
    (if (#{:left :right} dir)
      [:rect {:x (* g/TILE-SIZE x)
              :y (+ 10 (* g/TILE-SIZE y))
              :width g/TILE-SIZE
              :height (- g/TILE-SIZE (* 2 10))
              :fill "rgb(255, 40, 40)"}]
      [:rect {:x (+ 10 (* g/TILE-SIZE x))
              :y (* g/TILE-SIZE y)
              :width (- g/TILE-SIZE (* 2 10))
              :height g/TILE-SIZE
              :fill "rgb(255, 40, 40)"}])))

(defn render-game [game-state]
  [:svg
   {:width (* g/TILE-SIZE g/WIDTH)
    :height (* g/TILE-SIZE g/HEIGHT)}
   [:rect {:width (* g/TILE-SIZE g/WIDTH)
           :height (* g/TILE-SIZE g/HEIGHT)
           :fill "#8cff8c"}]
   [:g
    (map render-length-up (:length-ups @game-state))]
   [:g
    (map render-bomb-up (:bomb-ups @game-state))]
   [:g
    (map render-stone (:stones @game-state))]
   [:g
    (map render-wood (:woods @game-state))]
   [:g
    (map render-bomb (vals (:bombs @game-state)))]
   [:g
    (map render-explosion (:explosions @game-state))]
   [:g
    (map render-player (vals (:players @game-state)))]])

(r/render [render-game game-state] (js/document.getElementById "app"))

(defn get-key [e]
  "returns the key that was pressed from an event"
  (case (do (.preventDefault e) (.-keyCode e))
    37 :left
    38 :up
    39 :right
    40 :down
    32 :space
    nil))

(def keys-chan (chan))
(def events-chan (chan))

(defn handle-key-press [e action]
  "dispatches a key press to the event loop"
  (let [dir (get-key e)]
    (if dir
      (put! keys-chan [action dir]))))

(events/listen js/window (.-KEYDOWN events/EventType)
               #(handle-key-press % :down))
(events/listen js/window (.-KEYUP events/EventType)
               #(handle-key-press % :up))

(defn process-keys []
  "starts an event loop that process all the presses"
  (go-loop [pressed nil]
    (let [[action dir] (<! keys-chan)]
      (cond
        (= [:down :space] [action dir])
            (do
              (>! events-chan [:drop-bomb])
              (recur pressed))
        (and (not= pressed dir) (= action :down))
            (do
              (>! events-chan [:start-moving dir])
              (recur dir))
        (and (= pressed dir) (= action :up))
            (do
              (>! events-chan [:stop-moving])
              (recur nil))
        :else (recur pressed)))))

(def game-token
  (-> js/document
      (.getElementById "data")
      (.getAttribute "data-game-info")
      read-string))

(println game-token)

(defn sock-conn []
  (go
    (let [[action server game-id] game-token
          {:keys [ws-channel]} (<! (ws-ch server))]
      (if ws-channel
        (let [[_ me] (:message (<! ws-channel))]
          (println "I am" me)
          (>! ws-channel [action game-id])
          (loop []
            (let [[v p] (alts! [ws-channel events-chan])]
              (if (= ws-channel p)
                (reset! game-state (second (:message v)))
                (>! ws-channel v)))
            (recur)))))))

(defn run []
  (do
    (process-keys)
    (sock-conn)))

(run)