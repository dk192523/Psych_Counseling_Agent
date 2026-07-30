# -*- coding: utf-8 -*-
"""把 cases.json 按 issueTag 分类，生成 CounselingDocumentLoader 兼容的 Markdown 知识库。

文件名规则必须是 `xxx - ○○篇.md`（loader 取倒数第3、2个汉字作 status 标签）。
每个案例按第一个 issueTag 归入唯一主类别，避免多标签重复入库污染检索。
"""
import json
import os
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
DOC_DIR = os.path.join(HERE, "document")

# issueTag -> 两字类别（文件名末尾 = 类别 + 篇.md）
CATEGORY = {
    "自我成长": "成长",
    "心理健康": "心理",
    "职业工作": "职场",
    "父母议题": "父母",
    "情感恋爱": "情感",
    "子女议题": "子女",
    "婚姻议题": "婚姻",
    "学业教育": "学业",
    "其他议题": "其他",
    "其她议题": "其他",  # 源数据错别字，归并
}

def case_section(c):
    lines = [f"#### {c['title'].strip()}"]
    meta = f"日期：{c['date']}"
    if c.get("duration"):
        meta += f"｜时长：{c['duration']}"
    if c.get("personTags"):
        meta += f"｜人物：{'、'.join(c['personTags'])}"
    lines.append(meta)
    lines.append(f"议题：{'、'.join(c.get('issueTags', []))}；细分：{'、'.join(c.get('detailTags', []))}")
    if c.get("url"):
        lines.append(f"视频：{c['url']}（案例编号 {c['slug']}）")
    else:
        # 即使来源视频链接缺失，也保留 slug，供摘要命中后的逐字稿二级溯源使用。
        lines.append(f"案例编号 {c['slug']}（来源视频链接缺失）")
    intro = (c.get("intro") or "").strip()
    if intro:
        lines.append("")
        lines.append(intro)
    return "\n".join(lines)

def main():
    data = json.load(open(os.path.join(HERE, "cases.json"), encoding="utf-8"))
    cases = data["cases"]
    groups = defaultdict(list)
    for c in cases:
        tags = c.get("issueTags") or ["其他议题"]
        cat = CATEGORY.get(tags[0], "其他")
        groups[cat].append(c)

    os.makedirs(DOC_DIR, exist_ok=True)
    for cat, items in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        items.sort(key=lambda c: c["date"], reverse=True)
        head = (
            f"# 咨询师连麦案例 - {cat}篇\n\n"
            f"本文档整理自公开直播连麦案例库，共 {len(items)} 个案例。"
            f"每节为一次真实连麦的问题概述与咨询师的判断思路，含视频出处，仅供个人学习检索使用，"
            f"不代表咨询师本人观点，不构成心理或医疗建议。\n"
        )
        body = "\n\n".join(case_section(c) for c in items)
        fname = f"咨询师连麦案例 - {cat}篇.md"
        with open(os.path.join(DOC_DIR, fname), "w", encoding="utf-8", newline="\n") as f:
            f.write(head + "\n" + body + "\n")
        print(f"{fname}: {len(items)} cases")

if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
