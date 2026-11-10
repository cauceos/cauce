import type { Appearance } from './types'

// The widget's only look is the channel's (ADR 0014, W4): every colour and the typeface are custom
// properties set from its appearance, whose pairs the API holds at WCAG AA contrast. What is here is
// layout, spacing and the states every chat needs (focus, disabled, reduced motion), nothing of a brand.

export function themeOf(a: Appearance): string {
  return `:host{--c-primary:${a.primary_color};--c-on-primary:${a.on_primary_color};--c-bg:${a.background_color};`
    + `--c-text:${a.text_color};--font:${a.font_family};}`
}

export const STYLES = `
:host{all:initial}
*,*::before,*::after{box-sizing:border-box}
.root{font-family:var(--font);color:var(--c-text);font-size:16px;line-height:1.45}
.sr{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
button,textarea,input{font:inherit;color:inherit}
:focus-visible{outline:3px solid var(--c-primary);outline-offset:2px}
.launcher{position:fixed;right:16px;bottom:16px;z-index:2147483646;min-width:56px;height:56px;padding:0 18px;border:0;
  border-radius:28px;background:var(--c-primary);color:var(--c-on-primary);cursor:pointer;display:flex;align-items:center;
  gap:8px;box-shadow:0 4px 16px rgba(0,0,0,.2);font-weight:600}
.launcher:focus-visible{outline-color:var(--c-text);outline-offset:3px}
.launcher img{width:32px;height:32px;border-radius:50%;object-fit:cover}
.launcher svg{width:24px;height:24px;flex:none}
.panel{position:fixed;right:16px;bottom:88px;z-index:2147483647;width:min(380px,calc(100vw - 32px));
  height:min(600px,calc(100vh - 120px));display:flex;flex-direction:column;background:var(--c-bg);color:var(--c-text);
  border-radius:12px;box-shadow:0 8px 32px rgba(0,0,0,.25);overflow:hidden}
.panel[hidden]{display:none}
.head{display:flex;align-items:center;gap:10px;padding:12px 12px 12px 16px;background:var(--c-primary);color:var(--c-on-primary)}
.head img{width:36px;height:36px;border-radius:50%;object-fit:cover;flex:none}
.head h2{margin:0;font-size:1rem;font-weight:700}
.head .ai{margin:2px 0 0;font-size:.8125rem}
.head .titles{flex:1;min-width:0}
.icon{width:44px;height:44px;border:0;border-radius:8px;background:transparent;color:inherit;cursor:pointer;display:grid;
  place-items:center;flex:none}
.head .icon:focus-visible{outline-color:var(--c-on-primary);outline-offset:-3px}
.icon svg{width:22px;height:22px}
.log{flex:1;overflow-y:auto;margin:0;padding:16px;list-style:none;display:flex;flex-direction:column;gap:8px}
.msg{max-width:85%;padding:8px 12px;border-radius:12px;white-space:pre-wrap;overflow-wrap:anywhere}
.msg.agent{align-self:flex-start;border:1px solid currentColor}
.msg.user{align-self:flex-end;background:var(--c-primary);color:var(--c-on-primary)}
.status{min-height:1.4em;margin:0 16px;font-size:.875rem}
.notice{margin:0 16px 8px;padding:10px 12px;border:2px solid currentColor;border-radius:8px;font-size:.9375rem}
.notice[hidden]{display:none}
.notice p{margin:0}
.notice button{margin-top:8px}
.privacy{margin:0;padding:8px 16px 0;font-size:.8125rem}
.privacy a{color:inherit;text-decoration:underline}
.accept{display:flex;gap:8px;align-items:flex-start;padding:8px 16px 0;font-size:.875rem}
.accept input{width:20px;height:20px;margin:2px 0 0;accent-color:var(--c-primary);flex:none}
.composer{display:flex;gap:8px;padding:12px 16px 16px;align-items:flex-end}
.composer textarea{flex:1;min-height:44px;max-height:120px;padding:10px 12px;border:2px solid currentColor;
  border-radius:8px;background:var(--c-bg);resize:none}
.btn{min-height:44px;padding:0 16px;border:0;border-radius:8px;background:var(--c-primary);color:var(--c-on-primary);
  cursor:pointer;font-weight:600}
.btn:disabled{opacity:.6;cursor:not-allowed}
.presence{position:fixed;right:16px;bottom:84px;z-index:2147483646;max-width:min(300px,calc(100vw - 32px));
  display:flex;align-items:flex-start;gap:4px;padding:4px 4px 4px 12px;background:var(--c-bg);color:var(--c-text);
  border-radius:12px;box-shadow:0 4px 16px rgba(0,0,0,.2)}
.presence[hidden]{display:none}
.presence .say{flex:1;min-height:44px;padding:8px 0;border:0;background:transparent;text-align:left;cursor:pointer}
.presence .icon{width:36px;height:36px}
.cards{list-style:none;margin:8px 0 0;padding:0;display:flex;flex-direction:column;gap:8px;white-space:normal}
.card{display:flex;flex-direction:column;gap:4px;padding:10px 12px;border:1px solid currentColor;border-radius:10px}
.card p{margin:0;font-size:.875rem}
.card .price{font-weight:700}
.card .btn{align-self:flex-start;margin-top:4px}
@media (max-width:480px){
  .panel{inset:0;width:100%;height:100%;height:100dvh;border-radius:0;
    padding-bottom:env(safe-area-inset-bottom)}
  .launcher{right:12px;bottom:calc(12px + env(safe-area-inset-bottom))}
}
@media (prefers-reduced-motion:reduce){*{transition:none!important;animation:none!important;scroll-behavior:auto!important}}
`
