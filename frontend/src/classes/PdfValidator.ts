const PDF_MIME_TYPE = 'application/pdf'

export class PdfValidator {
  static isPdf(file: File): boolean {
    return (
      file.type === PDF_MIME_TYPE || file.name.toLowerCase().endsWith('.pdf')
    )
  }
}
