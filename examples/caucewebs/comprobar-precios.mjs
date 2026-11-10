#!/usr/bin/env node
// Compara el paquete de CauceWebs con lo que publica caucewebs.es y avisa de cada diferencia.
// Paso obligatorio al cambiar un precio (ver README.md). Sin dependencias: Node 18 o superior.
//
//   node examples/caucewebs/comprobar-precios.mjs
//   node examples/caucewebs/comprobar-precios.mjs --web https://staging.caucewebs.es
//
// Sale con 0 si todo coincide y con 1 si hay alguna diferencia o no encuentra un precio en la web.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const here = dirname(fileURLToPath(import.meta.url));
const web = option('--web', 'https://caucewebs.es').replace(/\/$/, '');
const catalog = JSON.parse(readFileSync(option('--catalogo', join(here, 'caucewebs', 'catalogo.json')), 'utf8'));
const sheet = JSON.parse(readFileSync(option('--ficha', join(here, 'caucewebs', 'ficha.json')), 'utf8'));

// Las páginas públicas donde salen precios y condiciones.
const PAGES = ['', 'precios', 'webs', 'tiendas', 'videos', 'preguntas', 'condiciones', 'condiciones-tienda', 'proximamente', 'recomienda'];

function text(html) {
  return html
    .replace(/<(script|style)[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)))
    // The site writes "20 %" and "49 €" with a non-breaking space; the pack, with a plain one.
    .replace(/&nbsp;|[  ]/g, ' ')
    .replace(/\s+/g, ' ');
}

const pages = {};
for (const page of PAGES) {
  const url = `${web}/${page}${page ? '/' : ''}`;
  const response = await fetch(url, { headers: { 'User-Agent': 'cauce-comprobar-precios' } });
  if (!response.ok) {
    console.error(`✗ ${url} responde ${response.status}: no se puede comparar.`);
    process.exit(1);
  }
  pages[page || 'portada'] = text(await response.text());
}

const everything = Object.values(pages).join(' ');
const euros = (s) => Number(s.replace(/\./g, '').replace(',', '.'));
const shown = (n) => (Number.isInteger(n) ? String(n) : n.toFixed(2).replace('.', ','));
const AMOUNT = '(\\d{1,4}(?:,\\d{2})?)\\s*€';
const items = Object.fromEntries(catalog.items.map((item) => [item.ref, item]));
const differences = [];
const ok = [];

// Cada precio del catálogo, contra la tabla de /precios/: la cifra que la web pone junto al plan.
const PUBLISHED = [
  { ref: 'plan-basico', field: 'price', page: 'precios', pattern: `Plan Básico ${AMOUNT} al año` },
  { ref: 'plan-basico-dominio', field: 'price', page: 'precios', pattern: `Con dominio propio: ${AMOUNT} al año` },
  { ref: 'plan-plus', field: 'price', page: 'precios', pattern: `Plan Plus ${AMOUNT} al año` },
  { ref: 'plan-tienda', field: 'price', page: 'precios', pattern: `Plan Tienda ${AMOUNT} al mes` },
  { ref: 'plan-tienda', field: 'cuota_anual', page: 'precios', pattern: `Plan Tienda \\d{1,4}(?:,\\d{2})? € al mes \\+ IVA o ${AMOUNT} al año` },
  { ref: 'plan-tienda', field: 'puesta_en_marcha', page: 'precios', pattern: `Puesta en marcha: ${AMOUNT}` },
];

for (const { ref, field, page, pattern } of PUBLISHED) {
  const item = items[ref];
  if (!item) {
    differences.push(`${ref}: no está en el catálogo, pero la web lo publica.`);
    continue;
  }
  const ours = field === 'price' ? item.price : item.attributes?.[field];
  const match = pages[page].match(new RegExp(pattern));
  if (!match) {
    differences.push(`${ref} (${field}): no encuentro su precio en ${web}/${page}/; revísalo a mano y ajusta este script si la página cambió.`);
  } else if (euros(match[1]) !== Number(ours)) {
    differences.push(`${ref} (${field}): el catálogo dice ${shown(Number(ours))} €, la web ${match[1]} €.`);
  } else {
    ok.push(`${ref} (${field}): ${match[1]} €`);
  }
}

// El programa de recomendación: cada cifra que /recomienda/ publica tiene que estar, tal cual, en la ficha.
const REFERRAL = [
  { fact: 'recomienda_bar_nuevo', what: 'descuento del bar nuevo', pattern: 'paga un (\\d{1,3}) % menos su primer año', unit: '%' },
  { fact: 'recomienda_bar_nuevo', what: 'Básico, primer año', pattern: `Básico ${AMOUNT} Su primer año` },
  { fact: 'recomienda_bar_nuevo', what: 'Básico con dominio, primer año', pattern: `Básico \\+ dominio ${AMOUNT} Su primer año` },
  { fact: 'recomienda_bar_nuevo', what: 'Plus, primer año', pattern: `Plus ${AMOUNT} Su primer año` },
  { fact: 'recomienda_quien_trae', what: 'premio del primer bar', pattern: `Tu primer bar ${AMOUNT} menos` },
  { fact: 'recomienda_quien_trae', what: 'premio de cada bar más', pattern: `Cada bar más ${AMOUNT} menos` },
  { fact: 'recomienda_quien_trae', what: 'porcentaje de cada bar más', pattern: 'cada uno de los siguientes, un (\\d{1,3}) %', unit: '%' },
];
for (const { fact, what, pattern, unit = '€' } of REFERRAL) {
  const ours = sheet.facts?.[fact];
  const match = pages.recomienda.match(new RegExp(pattern));
  if (!ours) {
    differences.push(`ficha, ${fact}: no está, pero ${web}/recomienda/ publica el programa.`);
  } else if (!match) {
    differences.push(`recomendación (${what}): no lo encuentro en ${web}/recomienda/; revísalo a mano y ajusta este script si la página cambió.`);
  } else if (!ours.includes(`${match[1]} ${unit}`)) {
    differences.push(`ficha, ${fact} (${what}): la web publica ${match[1]} ${unit} y la ficha no lo dice.`);
  } else {
    ok.push(`recomendación (${what}): ${match[1]} ${unit}`);
  }
}
for (const [key, value] of Object.entries(sheet.facts ?? {})) {
  for (const m of String(value).matchAll(/(\d{1,3}) %/g)) {
    if (!new RegExp(`(^|\\D)${m[1]} %`).test(everything)) {
      differences.push(`ficha, ${key}: escribe ${m[1]} %, que no sale en ninguna página de ${web}.`);
    }
  }
}

// Cada importe que escribe el paquete (descripciones, atributos y ficha) tiene que salir en alguna página.
const written = new Map();
const collect = (where, value) => {
  for (const m of String(value).matchAll(/(\d{1,4}(?:,\d{2})?)\s*€/g)) {
    if (!written.has(m[1])) written.set(m[1], where);
  }
};
for (const item of catalog.items) {
  collect(`catálogo, ${item.ref}`, item.description ?? '');
  collect(`catálogo, ${item.ref}`, JSON.stringify(item.attributes ?? {}));
}
for (const [key, value] of Object.entries(sheet.facts ?? {})) collect(`ficha, ${key}`, value);
for (const [amount, where] of written) {
  if (!new RegExp(`(^|[^\\d,])${amount.replace(',', '\\,')}\\s*€`).test(everything)) {
    differences.push(`${where}: escribe ${amount} €, que no sale en ninguna página de ${web}.`);
  }
}

// Lo previsto solo se nombra si la web pública ya lo enseña.
for (const item of catalog.items.filter((i) => i.attributes?.estado === 'previsto')) {
  if (pages.proximamente.includes(item.name)) {
    ok.push(`${item.ref}: previsto, y la web lo enseña en /proximamente/`);
  } else {
    differences.push(`${item.ref}: está como previsto, pero ${web}/proximamente/ no lo enseña; quítalo del catálogo.`);
  }
}

ok.forEach((line) => console.log(`✓ ${line}`));
differences.forEach((line) => console.log(`✗ ${line}`));
console.log(differences.length === 0
  ? `\nEl paquete coincide con ${web}.`
  : `\n${differences.length} diferencia(s) con ${web}: corrige el paquete (o la web) antes de commitear.`);
process.exit(differences.length === 0 ? 0 : 1);
