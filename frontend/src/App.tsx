import { Navigate, Route, Routes } from 'react-router-dom'

import { DropPdfPage } from './features/droppdfpage/index.ts'
import { PdfReaderPage } from './features/pdfreaderpage/index.ts'

function App() {
  return (
    <Routes>
      <Route path="/" element={<DropPdfPage />} />
      <Route path="/viewer" element={<PdfReaderPage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default App
