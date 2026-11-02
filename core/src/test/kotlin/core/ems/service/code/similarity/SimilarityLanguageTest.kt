package core.ems.service.code.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SimilarityLanguageTest {

    @Test
    fun `an exercise Silmused grades is SQL, so one that kept lahendus py is not read as Python`() {
        assertEquals(SimilarityLanguage.SQL, SimilarityLanguage.of("lahendus.py", "silmused"))
        assertEquals(SimilarityLanguage.SQL, SimilarityLanguage.of("lahendus.py", "silmused:1.8"))
        assertEquals(SimilarityLanguage.PYTHON, SimilarityLanguage.of("lahendus.py", "tiivad"))
        assertEquals(SimilarityLanguage.PYTHON, SimilarityLanguage.of("lahendus.py", null))
        assertEquals(SimilarityLanguage.SQL, SimilarityLanguage.of("lahendus.SQL", null))
        assertEquals(SimilarityLanguage.TEXT, SimilarityLanguage.of("essee.txt", null))
        assertEquals(SimilarityLanguage.TEXT, SimilarityLanguage.of("Lahendus.java", null))
    }
}
