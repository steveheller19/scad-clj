(ns scad-clj.gear
  (:require [scad-clj.model :refer :all :exclude [import use]]
            [scad-clj.scad :refer :all]
            [clojure.core.matrix :refer [matrix mmul]])
  (:import [java.lang.Math]))

(def ^:dynamic *n-involute* 5)
(def epsilon 1e-9)

(defn sq [x]
  (* x x))

;; (defn rad->deg [a]
;;   (* 360 (/ a tau)))

(defn rot-z [t]
  (matrix [[(Math/cos t)     (Math/sin t) 0.]
           [(- (Math/sin t)) (Math/cos t) 0.]
           [0.               0.           1.]]))

(defn involute-curve [i]
  (fn [t]
    (let [x (* i t)]
      (mmul (rot-z (- t))
            (matrix [x i 0])))))

(defn samples [min max n]
  (let [diff (- max min)]
    (map #(+ min (* (/ % (dec n)) diff))
         (range n))))

(defn base-radius [{:keys [pitch-radius pressure-angle]}]
  (* pitch-radius (Math/cos pressure-angle)))

(defn dedendum [& {:keys [pitch-radius diametrical-pitch pressure-angle] :as args}]
  (- pitch-radius (base-radius args)))

(defn profile [{:keys [pitch-radius pressure-angle addendum] :as args}]
  (let [addendum-radius (+ pitch-radius addendum)
        base-radius (base-radius args)
        
        theta-addendum (Math/sqrt (- (sq (/ addendum-radius base-radius)) 1))
        theta-pitch (Math/sqrt (- (sq (/ pitch-radius base-radius)) 1))
        
        curve (involute-curve base-radius)
        [p-x _ _] (curve theta-pitch)

        alpha-pitch (Math/asin (/ p-x pitch-radius))]
    (rotate alpha-pitch [0 0 1]
            (polygon (conj (map (fn [[x y z]] [x y])
                                (map curve
                                     (samples 0 theta-addendum *n-involute*)))
                           [0 0])))))

(defn tooth [{:keys [pitch-radius radial-pitch pressure-angle addendum toothiness]
              :or {toothiness 0.5} :as args}]
  (let [profile (profile args)
        addendum-radius (+ pitch-radius addendum)]
    (hull
     profile
     (rotate (/ (* toothiness tau) radial-pitch pitch-radius) [0 0 1]
             (mirror [1 0 0]
                     profile)))))

;; radial-pitch: teath per tau units of circumference
(defn external-gear [{:keys [pitch-radius radial-pitch pressure-angle
                    tooth-ratio addendum] :as args}]
  (let [tooth (tooth args)]
    (rotate (/ tau 4) [0 0 -1]
            (union
             (circle (base-radius args))
             (apply union
                    (map (fn [i] (rotate (* i (/ tau radial-pitch pitch-radius)) [0 0 1] tooth))
                         (range (* radial-pitch pitch-radius))))))))

(defn internal-gear [{:keys [pitch-radius radial-pitch pressure-angle
                             tooth-ratio addendum
                             outer-radius inner-radius] :as args}]
  (rotate (/ (angular-width args) 2.) [0 0 1]
   (difference
    (circle outer-radius)
    (external-gear (assoc args
                     :toothiness (- 1. (:toothiness args))))
    (circle inner-radius))))

(defn angular-width [{:keys [pitch-radius radial-pitch] :as args}]
  (/ tau radial-pitch pitch-radius))

(defn tooth-position [{:keys [pitch-radius radial-pitch] :as args} rotation angle]
  (/ (rem (- angle rotation) (angular-width args))
     (angular-width args)))

(defn mate-to [args1 rot1 args2 rot2 theta]
  (let [pos1 (tooth-position args1 rot1 theta)
        pos2 (tooth-position args2 rot2 (+ theta (/ tau 2)))
        tgt (- 1 pos1)
        phi (* (- pos2 tgt) (angular-width args2))]
    ;; [pos1 pos2 tgt phi]
    phi))

(defn extrude-herringbone [{:keys [height radius angle]} & block]
  (let [height-p (+ (/ height 2.) epsilon)
        arc-len (* height-p (Math/tan angle))
        twist (/ arc-len radius)]
    (union
     (apply extrude-linear {:height height-p
                            :twist twist} block)
     (translate [0 0 height]
                (mirror [0 0 1]
                        (apply extrude-linear {:height height-p
                                               :twist twist} block))))))

(defn revolve [radius1 radius2 theta & block]
  (rotate theta [0 0 1]
          (translate
           [(+ radius1 radius2) 0 0]
           (rotate (/ (* theta radius1) radius2) [0 0 1]
                   (apply rotate (/ tau 2.) [0 0 1]
                          block)))))

(defn- normalize-delta-pos [x]
  (if (< 0 x)
    (- x (Math/floor x))
    (- x (Math/floor x))))

(defn- mate-planetary [sun-args planet-args ring-args theta]
  (let [width (angular-width ring-args)
        parity (mod (* (:pitch-radius planet-args)
                       (:radial-pitch planet-args)) 2)
        ring-pos (tooth-position ring-args 0 theta)
        planet-pos (tooth-position ring-args 0
                                   (- (* width (/ parity 2.)) theta))
        delta-pos (normalize-delta-pos (- ring-pos planet-pos 0.5))
        delta-angle (* delta-pos width (/ 3. 4.))]
    (prn [parity ring-pos planet-pos delta-pos])
    (+ theta (* 0.9 delta-angle))))

(defn planetary [sun-args planet-args & {:keys [n height angle outer-radius]}]
  (let [radius (+ (:pitch-radius sun-args)
                  (* 2 (:pitch-radius planet-args)))
        ring-args (assoc planet-args
                    :pitch-radius radius
                    :outer-radius outer-radius
                    :inner-radius (+ (:pitch-radius sun-args)
                                     (:pitch-radius planet-args)
                                     (base-radius planet-args)
                                     (* 0.3 (:addendum planet-args)))
                    :addendum (* 1.3 (:addendum planet-args)))
        ring (internal-gear ring-args)
        sun (external-gear sun-args)
        planet (rotate 0.01 [0 0 1]
                       (external-gear planet-args))]
    (union
     (extrude-herringbone
      {:height height, :angle angle, :radius (:pitch-radius sun-args)}
      ring)

     (extrude-herringbone
      {:height height, :angle angle, :radius (:pitch-radius sun-args)}
      sun)

     (union
      (map
       (bound-fn [i]
         (let [theta (* i (/ tau n))]
           (revolve (:pitch-radius sun-args) (:pitch-radius planet-args)
                    (mate-planetary sun-args planet-args ring-args theta)
                    (extrude-herringbone
                     {:height height, :angle angle :radius (:pitch-radius planet-args)}
                     planet))))
       (range n))))))

;; sample spur gear

(def args1 {:pitch-radius 10
            :radial-pitch 3.0
            :pressure-angle (* tau (/ 20 360))
            :addendum 0.3
            :toothiness 0.45})
(def args2 (assoc args1
             :pitch-radius 5))

(comment
  (extrude-linear {:height 3}
                  (union
                   (external-gear args1)
                   (translate [15 0 0]
                              (rotate
                               (mate-to args1 0 args2 0 0) [0 0 1]
                               (external-gear args2))))))

(comment
  (planetary args1 args2
             :n 7, :height 3, :angle (/ tau 8)
             :outer-radius 25))
