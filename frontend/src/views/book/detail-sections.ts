/**
 * 图书详情的字段分组配置。
 *
 * <h3>为什么是一个函数而不是一个常量数组</h3>
 * 大部分字段是「取值 + 给个标签」的直接映射，但有几个要算：
 * - `categories` 是对象数组，要拍平成 `「文学-古典文学」` 这样的字符串
 * - `copyrightChapterUids` 可能上千个数字，全铺成标签会把抽屉撑爆，只显示个数
 * - `newRatingDetail` / `maxFreeInfo` 是嵌套对象，要拆成几个独立条目
 *
 * 写成 `(book) => DetailSection[]` 之后，这些派生值就在配置里就地算完了，
 * 页面里不用再写一堆 `v-if`。
 *
 * <h3>字段清单怎么来的</h3>
 * 不是照着 Java 实体抄的 —— 是**拿真实接口返回的 54 个键**和 TS 类型逐个比对出来的。
 * 这一步抓到了一个静默缺陷：Java 字段叫 `lPushName`，但 Jackson 序列化出来是
 * `lpushName`，前端类型跟着 Java 写就会永远读到 `undefined`。
 * 详见 `types/index.ts` 里 `lpushName` 的注释。
 *
 * <p>54 个接口键里，`newRatingDetail` / `maxFreeInfo` / `categories` 是嵌套结构，
 * 展开成独立条目后一共 **60 条**（`countDetailItems()` 会算出来，
 * 页面上显示的就是这个数，避免写死一个数字后跟配置对不上）。
 * 分 5 组：基本信息 / 评分与热度 / 价格与免费 / 出版与版权 / 采集元信息，
 * 顺序按「用户想看的优先级」排，第一组默认展开。
 */

import type { Book } from '@/types'
import type { DetailSection } from '@/components/DetailDrawer.vue'
import { flagText } from '@/utils/format'

/** `[{title:'文学-古典文学'}]` → `['文学-古典文学']` */
function categoryTitles(book: Book): string[] {
  if (!Array.isArray(book.categories)) {
    return []
  }
  return book.categories
    .map((c) => c?.title)
    .filter((t): t is string => typeof t === 'string' && t !== '')
}

export function buildBookDetailSections(book: Book): DetailSection[] {
  return [
    {
      title: '基本信息',
      items: [
        { label: '书名', value: book.title, block: true },
        { label: '作者', value: book.author },
        { label: '译者', value: book.translator },
        { label: '平台分类', value: book.category, block: true },
        { label: '分类标签', value: categoryTitles(book), tags: true, block: true },
        { label: '语言', value: book.language },
        { label: '封面', value: book.cover, image: true },
        { label: '图书 ID', value: book.bookId, mono: true, block: true },
        {
          label: '简介',
          value: book.intro,
          block: true,
        },
      ],
    },
    {
      title: '评分与热度',
      items: [
        // 🔴 顶层 newRating，不是 newRatingDetail.newRating（后者不存在）
        { label: '推荐值', value: book.newRating },
        { label: '评价人数', value: book.newRatingCount },
        { label: '评价标题', value: book.newRatingTitle },
        { label: '阅读人数', value: book.readingCount },
        { label: '好评', value: book.newRatingDetail?.good },
        { label: '中评', value: book.newRatingDetail?.fair },
        { label: '差评', value: book.newRatingDetail?.poor },
        { label: '近期评价', value: book.newRatingDetail?.recent },
        { label: '评价标签', value: book.newRatingDetail?.title },
        { label: '有讲书', value: flagText(book.hasLecture) },
        { label: '最新章节序号', value: book.lastChapterIdx },
        { label: '搜索序号', value: book.searchIdx },
        { label: 'typeInfo', value: book.typeInfo },
      ],
    },
    {
      title: '价格与免费',
      items: [
        { label: '价格（元）', value: book.price },
        { label: '原价（元）', value: book.originalPrice },
        { label: '价格（分）', value: book.centPrice },
        { label: '是否免费', value: flagText(book.free) },
        { label: '付费状态', value: book.payingStatus },
        { label: '付费类型', value: book.payType },
        { label: '会员折扣', value: book.mcardDiscount },
        { label: '最大免费章节', value: book.maxFreeChapter },
        { label: '免费章节序号', value: book.maxFreeInfo?.maxFreeChapterIdx },
        { label: '免费章节 UID', value: book.maxFreeInfo?.maxFreeChapterUid },
        { label: '免费比例', value: book.maxFreeInfo?.maxFreeChapterRatio },
        { label: '是否售罄', value: flagText(book.soldout) },
      ],
    },
    {
      title: '出版与版权',
      items: [
        { label: '是否出版', value: flagText(book.ispub) },
        { label: '是否完结', value: flagText(book.finished) },
        { label: '出版时间', value: book.publishTime },
        { label: '纸书 SKU', value: book.paperBookSkuId, mono: true },
        { label: 'cpid', value: book.cpid },
        { label: '书籍类型', value: book.type },
        { label: '格式', value: book.format },
        { label: '版本', value: book.version },
        { label: '书籍状态', value: book.bookStatus },
        { label: 'extraType', value: book.extraType },
        { label: '禁止保存图片', value: flagText(book.blockSaveImg) },
        { label: '繁体中文', value: book.isTraditionalChinese },
        { label: '隐藏更新时间', value: book.hideUpdateTime },
        { label: 'EPUB 漫画', value: flagText(book.isEpubComics) },
        { label: '竖排版', value: flagText(book.isVerticalLayout) },
        { label: '支持朗读', value: flagText(book.isShowTts) },
        { label: '网页端管控', value: book.webBookControl },
        { label: '自出版激励', value: book.selfProduceIncentive },
        { label: '自动下载', value: flagText(book.isAutoDownload) },
        {
          label: '版权章节',
          // 可能上千个 UID，铺成标签会把抽屉撑爆，只给个数
          value: Array.isArray(book.copyrightChapterUids)
            ? `${book.copyrightChapterUids.length} 个章节 UID`
            : null,
        },
        // ⚠️ 小写 lpush —— 详见 types/index.ts 的注释
        { label: 'lpushName', value: book.lpushName, mono: true, block: true },
        { label: '作者 VIDs', value: book.authorVids, mono: true, block: true },
      ],
    },
    {
      title: '采集元信息',
      items: [
        { label: 'Mongo 主键', value: book.id, mono: true, block: true },
        // 这两个是 UTC ISO 字符串，必须过 new Date() 才是本地时间
        { label: '首次采集', value: book.firstCollectedAt, time: true },
        { label: '最近采集', value: book.lastCollectedAt, time: true },
        { label: '采集来源', value: book.collectSource, tags: true, block: true },
      ],
    },
  ]
}

/**
 * 列配置里「详情抽屉里一共有多少个字段」的口径。
 *
 * 单独抽出来是为了让页面上的文案和配置不会各说各话 ——
 * 以前写死的「46 个字段」是原 Python 分析时的数，接口实际返 54 个键。
 */
export function countDetailItems(sections: DetailSection[]): number {
  return sections.reduce((sum, s) => sum + s.items.length, 0)
}
