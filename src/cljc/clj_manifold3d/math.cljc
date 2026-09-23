(ns clj-manifold3d.math
  "Portable numeric helpers for modeling code shared by journals and the JVM."
  (:refer-clojure :exclude [abs]))
(def pi "The circle constant, in radians." #?(:clj Math/PI :cljs js/Math.PI))
(defn sin "Sine of an angle in radians." [x] (#?(:clj Math/sin :cljs js/Math.sin) x))
(defn cos "Cosine of an angle in radians." [x] (#?(:clj Math/cos :cljs js/Math.cos) x))
(defn sqrt "Nonnegative square root." [x] (#?(:clj Math/sqrt :cljs js/Math.sqrt) x))
(defn pow "Raise x to the power y." [x y] (#?(:clj Math/pow :cljs js/Math.pow) x y))
(defn abs "Absolute value." [x] (#?(:clj Math/abs :cljs js/Math.abs) (double x)))
(defn floor "Greatest integer no greater than x." [x] (#?(:clj Math/floor :cljs js/Math.floor) x))
(defn atan2 "Angle of [x y], in radians." [y x] (#?(:clj Math/atan2 :cljs js/Math.atan2) y x))
(defn hypot "Length of the vector [x y], without intermediate overflow." [x y] (#?(:clj Math/hypot :cljs js/Math.hypot) x y))
