// The widget's own texts, fixed per locale (ADR 0014, WC5): the AI notice and the privacy line are not
// settings. Everything else a person reads (name, greeting, placeholder, the unavailable message and
// the contact) comes from the channel's appearance.

/** The version of the notice text a person accepts; it enters the session's consent hash. */
export const NOTICE_VERSION: Record<'es' | 'en', string> = { es: 'es-1', en: 'en-1' }

export interface Texts {
  aiNotice: string
  privacyNotice: string
  privacyLink: string
  acceptPrivacy: string
  acceptFirst: string
  open: string
  close: string
  send: string
  messageLabel: string
  placeholder: string
  you: string
  typing: string
  unavailable: string
  contactPrefix: string
  rateLimited: (seconds: number) => string
  threadFull: string
  startOver: string
  invalid: string
  dismiss: string
  cards: string
  addToCart: string
}

export const TEXTS: Record<'es' | 'en', Texts> = {
  es: {
    aiNotice: 'Estás hablando con un asistente de inteligencia artificial.',
    privacyNotice: 'Lo que escribas se usa para responderte.',
    privacyLink: 'Política de privacidad',
    acceptPrivacy: 'He leído y acepto la política de privacidad',
    acceptFirst: 'Para escribir, acepta antes la política de privacidad.',
    open: 'Abrir el chat',
    close: 'Cerrar el chat',
    send: 'Enviar',
    messageLabel: 'Tu mensaje',
    placeholder: 'Escribe tu mensaje',
    you: 'Tú',
    typing: 'Escribiendo…',
    unavailable: 'Ahora mismo no puedo responder. Inténtalo un poco más tarde.',
    contactPrefix: 'También puedes escribir a',
    rateLimited: (seconds) => `Vas muy rápido. Espera ${seconds} s y vuelve a intentarlo.`,
    threadFull: 'Esta conversación ya es muy larga.',
    startOver: 'Empezar una nueva',
    invalid: 'El mensaje está vacío o es demasiado largo.',
    dismiss: 'Cerrar el aviso',
    cards: 'Productos',
    addToCart: 'Añadir',
  },
  en: {
    aiNotice: 'You are talking to an artificial intelligence assistant.',
    privacyNotice: 'What you write is used to answer you.',
    privacyLink: 'Privacy policy',
    acceptPrivacy: 'I have read and accept the privacy policy',
    acceptFirst: 'To write, please accept the privacy policy first.',
    open: 'Open the chat',
    close: 'Close the chat',
    send: 'Send',
    messageLabel: 'Your message',
    placeholder: 'Type your message',
    you: 'You',
    typing: 'Typing…',
    unavailable: 'I cannot answer right now. Please try again a little later.',
    contactPrefix: 'You can also write to',
    rateLimited: (seconds) => `You are going too fast. Wait ${seconds} s and try again.`,
    threadFull: 'This conversation is already very long.',
    startOver: 'Start a new one',
    invalid: 'The message is empty or too long.',
    dismiss: 'Dismiss',
    cards: 'Products',
    addToCart: 'Add to cart',
  },
}
