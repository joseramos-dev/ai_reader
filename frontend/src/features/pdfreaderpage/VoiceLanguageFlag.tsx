interface VoiceLanguageFlagProps {
  languageHint: string
  className?: string
}

function EnglandFlag({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 30 20"
      className={className}
      aria-hidden="true"
      role="img"
    >
      <rect width="30" height="20" fill="#fff" />
      <rect x="12.5" width="5" height="20" fill="#CE1124" />
      <rect y="7.5" width="30" height="5" fill="#CE1124" />
    </svg>
  )
}

function SpainFlag({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 30 20"
      className={className}
      aria-hidden="true"
      role="img"
    >
      <rect width="30" height="20" fill="#AA151B" />
      <rect y="5" width="30" height="10" fill="#F1BF00" />
    </svg>
  )
}

export function getVoiceLanguageLabel(languageHint: string): string {
  switch (languageHint.toLowerCase()) {
    case 'es':
      return 'Español'
    case 'en':
      return 'Inglés'
    default:
      return languageHint.toUpperCase()
  }
}

export function VoiceLanguageFlag({
  languageHint,
  className = 'h-3.5 w-5 shrink-0 rounded-sm border border-zinc-200 dark:border-zinc-600',
}: VoiceLanguageFlagProps) {
  const lang = languageHint.toLowerCase()
  const label = getVoiceLanguageLabel(languageHint)

  if (lang === 'en') {
    return (
      <span className="inline-flex shrink-0" title={label} aria-label={label}>
        <EnglandFlag className={className} />
      </span>
    )
  }

  if (lang === 'es') {
    return (
      <span className="inline-flex shrink-0" title={label} aria-label={label}>
        <SpainFlag className={className} />
      </span>
    )
  }

  return (
    <span
      className="shrink-0 rounded px-1.5 py-0.5 text-xs font-semibold uppercase text-zinc-500 dark:text-zinc-400"
      aria-label={label}
    >
      {languageHint.toUpperCase()}
    </span>
  )
}
