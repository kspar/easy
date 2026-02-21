import type { Extension } from '@codemirror/state'

export async function languageFromFilename(filename: string): Promise<Extension> {
  const ext = filename.split('.').pop()?.toLowerCase()
  switch (ext) {
    case 'py':
      return import('@codemirror/lang-python').then((m) => m.python())
    case 'js':
    case 'jsx':
      return import('@codemirror/lang-javascript').then((m) => m.javascript({ jsx: true }))
    case 'ts':
    case 'tsx':
      return import('@codemirror/lang-javascript').then((m) =>
        m.javascript({ jsx: true, typescript: true }),
      )
    case 'java':
      return import('@codemirror/lang-java').then((m) => m.java())
    case 'c':
    case 'h':
    case 'cpp':
    case 'cc':
    case 'cxx':
    case 'hpp':
      return import('@codemirror/lang-cpp').then((m) => m.cpp())
    case 'html':
    case 'htm':
      return import('@codemirror/lang-html').then((m) => m.html())
    case 'css':
      return import('@codemirror/lang-css').then((m) => m.css())
    case 'sql':
      return import('@codemirror/lang-sql').then((m) => m.sql())
    default:
      return []
  }
}
