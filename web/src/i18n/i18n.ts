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

i18n.on('languageChanged', (lng) => {
  localStorage.setItem(LANGUAGE_KEY, lng)
})

export default i18n
