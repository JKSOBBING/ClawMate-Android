import { CollectionEventViewModel, CollectionGalleryDetailViewModel, CollectionGroupViewModel,
  CollectionImageViewModel, CollectionViewModel } from '../datamanager/DataManagerTypes';
import { galleryMemorySceneIdForCategory } from '../utils/gallery/GalleryMemorySync';

// A completed gallery result is already structured. Make it available until the full memory sync indexes its source.
export function addGalleryDomainRecords(view: CollectionViewModel, scene: number, items: CollectionGalleryDetailViewModel[]): CollectionViewModel {
  const indexed = new Set<string>();
  view.groups.forEach((group: CollectionGroupViewModel): void => {
    group.events.forEach((event: CollectionEventViewModel): void => {
      event.galleryDetails.forEach((item: CollectionGalleryDetailViewModel): void => { indexed.add(item.id); });
    });
  });
  const additions = items.filter((item: CollectionGalleryDetailViewModel): boolean =>
    galleryMemorySceneIdForCategory(item.category) === scene && !indexed.has(item.id));
  if (additions.length === 0) { return view; }
  additions.sort((a: CollectionGalleryDetailViewModel, b: CollectionGalleryDetailViewModel): number => b.date.localeCompare(a.date));
  const groups: CollectionGroupViewModel[] = [];
  for (const item of additions) {
    const id = 'gallery-result:' + item.id;
    const image: CollectionImageViewModel = { ref: item.id, uri: item.uri, available: !!item.uri, label: item.title };
    const event: CollectionEventViewModel = { id: id, summary: item.summary || item.title, fullText: item.summary,
      normalizedFact: item.summary, source: '图库分析', time: item.date, firstSeenTime: item.date, lastSeenTime: item.date,
      meta: item.category, status: '已入库', domain: item.category, imageRefs: item.uri ? [image] : [],
      galleryDetails: [item], thumbnailUri: item.uri };
    groups.push({ id: id, title: item.title || item.summary, source: event.source, time: item.date, meta: item.category,
      status: event.status, domain: item.category, eventCount: 1, thumbnailUri: item.uri, events: [event] });
    view.rows.push({ id: id, title: event.summary, source: event.source, time: item.date, meta: item.category,
      status: event.status, domain: item.category });
  }
  view.groups = groups.concat(view.groups); view.empty = false;
  view.countLabel = String(view.rows.length) + ' 条';
  if (!view.lastUpdatedLabel || view.lastUpdatedLabel === '暂无同步') { view.lastUpdatedLabel = additions[0].date; }
  return view;
}
