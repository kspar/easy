import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import et from './et.json'
import en from './en.json'

const LANGUAGE_KEY = 'language'

const savedLang = localStorage.getItem(LANGUAGE_KEY) ?? 'et'

i18n.use(initReactI18next).init({
  resources: {
    et: { translation: et },
    en: { translation: en },
  },
  lng: savedLang,
  fallbackLng: 'et',
  interpolation: {
    escapeValue: false,
  },
})

/**
 * Keeps `<html lang>` on the language actually being displayed.
 *
 * `index.html` ships `lang="et"` and, until this, nothing ever changed it — so every English-UI
 * user was served a page that declared itself Estonian. That is wrong for a screen reader, which
 * picks its voice and its pronunciation rules from this attribute and would read English text with
 * Estonian phonetics; it is wrong for the browser's spellchecker in every `<textarea>` on the site;
 * and it is what makes Chrome offer to translate a page that is already in the reader's language.
 *
 * That last one is not cosmetic. A translated page rewrites the text nodes React is holding
 * references to, which is the crash in EZ-1884 — see `components/SafeText.tsx`. Declaring the
 * language honestly does not make the app translation-proof (an Estonian page read by an English
 * speaker is a translation the student actually wants), but it stops the app from inviting a
 * translation nobody asked for.
 *
 * Set here rather than in a component: it is one attribute on a document that exists before React
 * does, and a `useEffect` somewhere would be a rule every future entry point has to remember.
 */
function syncDocumentLanguage(lng: string) {
  // `lng` can carry a region — i18next resolves `en-GB` to the `en` bundle but reports the tag it
  // was given — and a region tag is valid here, so it goes in unmodified.
  document.documentElement.lang = lng
}

syncDocumentLanguage(i18n.language)

i18n.on('languageChanged', (lng) => {
  localStorage.setItem(LANGUAGE_KEY, lng)
  syncDocumentLanguage(lng)
})

export default i18n
