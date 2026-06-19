import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import './index.css'
import App from './App.tsx'
import { PdfDocumentProvider } from './public/context/PdfDocumentContext.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <PdfDocumentProvider>
        <App />
      </PdfDocumentProvider>
    </BrowserRouter>
  </StrictMode>,
)
