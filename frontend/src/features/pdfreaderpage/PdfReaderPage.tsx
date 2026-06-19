import { Navigate, useNavigate } from 'react-router-dom'

import { getDocumentFileUrl } from '../../api/documentsApi'
import { usePdfDocument } from '../../public/hooks/usePdfDocument'
import { PdfViewer } from './PdfViewer'

export function PdfReaderPage() {
  const { document, clearDocument } = usePdfDocument()
  const navigate = useNavigate()

  if (!document) {
    return <Navigate to="/" replace />
  }

  function handleChangePdf() {
    clearDocument()
    navigate('/')
  }

  return (
    <main className="flex min-h-svh flex-col bg-zinc-100 dark:bg-zinc-950">
      <PdfViewer
        documentId={document.id}
        fileName={document.filename}
        fileUrl={getDocumentFileUrl(document.id)}
        initialPage={document.pageActual}
        initialCapitulos={document.capitulos}
        initialResumenes={document.resumenes}
        onChangePdf={handleChangePdf}
      />
    </main>
  )
}
