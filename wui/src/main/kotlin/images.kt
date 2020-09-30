import libheaders.Materialize
import rip.kspar.ezspa.getNodelistBySelector

fun lightboxExerciseImages() {
    Materialize.Materialbox.init(getNodelistBySelector("#exercise-text img"))
}
