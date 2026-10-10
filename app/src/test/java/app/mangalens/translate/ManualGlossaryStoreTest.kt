package app.mangalens.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualGlossaryStoreTest {
    @Test
    fun `manual term is protected and restored without matching inside longer words`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("mangalens_manual_glossary", 0).edit().clear().commit()
        val store = ManualGlossaryStore(context)
        store.put("Miss", "Madame")

        val protected = store.protect("Miss Alice, I missed the train.")
        assertFalse(protected.text.contains("Miss Alice"))
        assertEquals("Madame Alice, I missed the train.", store.restore(protected.text, protected))
    }

    @Test
    fun `phrase rules prefer the longer term`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("mangalens_manual_glossary", 0).edit().clear().commit()
        val store = ManualGlossaryStore(context)
        store.put("Miss", "Mademoiselle")
        store.put("Miss Alice", "Madame Alice")
        val protected = store.protect("Miss Alice is here.")
        assertEquals(1, protected.markers.size)
        assertEquals("Madame Alice is here.", store.restore(protected.text, protected))
    }
}
