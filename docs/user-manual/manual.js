/* All content and search stay on this device. No network requests or persistence. */
const search = document.querySelector('#search');
const sections = [...document.querySelectorAll('main section')];
const navLinks = [...document.querySelectorAll('nav a')];
function filter() {
  const terms = search.value.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
  let count = 0;
  for (const [index, section] of sections.entries()) {
    const text = section.textContent.toLocaleLowerCase();
    const show = terms.every(term => text.includes(term));
    section.hidden = !show;
    navLinks[index].hidden = !show;
    if (show) count++;
  }
  document.querySelector('#empty').hidden = count > 0;
  document.querySelector('#search-status').textContent = terms.length ? `找到 ${count} 个相关章节，清空搜索可查看全部。` : '按任务查阅，随时回来。';
}
search.addEventListener('input', filter);
// Quick links and in-chapter cross references may point outside current results.
document.addEventListener('click', event => {
  const anchor = event.target.closest('a[href^="#"]');
  if (anchor && anchor.hash !== '#top' && document.querySelector(anchor.hash)?.hidden) {
    search.value = '';
    filter();
  }
});
let detailsBeforePrint = [];
function showAllForPrint() {
  sections.forEach(section => { section.hidden = false; });
  detailsBeforePrint = [...document.querySelectorAll('details')].map(detail => [detail, detail.open]);
  detailsBeforePrint.forEach(([detail]) => { detail.open = true; });
}
window.addEventListener('beforeprint', showAllForPrint);
window.addEventListener('afterprint', () => {
  detailsBeforePrint.forEach(([detail, open]) => { detail.open = open; });
  filter();
});
const printButton = document.querySelector('#print');
printButton.hidden = false;
printButton.addEventListener('click', () => window.print());
const viewer = document.querySelector('#image-viewer');
const large = document.querySelector('#large-image');
if (typeof viewer.showModal === 'function') {
  document.querySelectorAll('.zoom').forEach(anchor => anchor.addEventListener('click', event => {
    event.preventDefault();
    large.src = anchor.href;
    large.alt = anchor.querySelector('img').alt;
    viewer.showModal();
  }));
  document.querySelector('#close-image').addEventListener('click', () => viewer.close());
  viewer.addEventListener('click', event => { if (event.target === viewer) viewer.close(); });
}
