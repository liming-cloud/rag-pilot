import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { Citation, DataCitation } from "./api";

/** [n] 换成指向 #cite-n 的链接，渲染时再换成可以点开原文的按钮；对不上的编号去掉（后端只保留真实存在的引用）。 */
function withCitationLinks(text: string, known: Set<number>) {
  return text.replace(/\[(\d+)\](?!\()/g, (_, index: string) => (known.has(Number(index)) ? `[${index}](#cite-${index})` : ""));
}

/**
 * 回答正文按 Markdown 显示（#145 A1）：换行、编号步骤、表格照原样排版。不渲染原始 HTML（资料或模型输出里夹带的
 * 标签当作文字丢掉），链接只保留引用编号，其他链接当普通文字显示，避免把员工带到资料里写的外部地址。
 * 数据出处（#182）和资料共用一套编号，点开的是数据的来源、条件和时间。
 */
export function AnswerMarkdown({
  text,
  citations,
  dataCitations = [],
  onCite,
  onCiteData = () => undefined,
}: {
  text: string;
  citations: Citation[];
  dataCitations?: DataCitation[];
  onCite: (citation: Citation) => void;
  onCiteData?: (citation: DataCitation) => void;
}) {
  const byIndex = new Map(citations.map((citation) => [citation.index, citation]));
  const dataByIndex = new Map(dataCitations.map((citation) => [citation.index, citation]));
  return (
    <div className="answer-markdown flex flex-col gap-2 leading-relaxed">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        skipHtml
        components={{
          a: ({ href, children }) => {
            const index = href?.startsWith("#cite-") ? Number(href.slice(6)) : undefined;
            const citation = index === undefined ? undefined : byIndex.get(index);
            const data = index === undefined ? undefined : dataByIndex.get(index);
            if (data) {
              return (
                <button
                  type="button"
                  aria-label={`查看数据出处 ${data.index}`}
                  onClick={() => onCiteData(data)}
                  className="mx-0.5 rounded-sm bg-accent px-1 align-super text-xs text-accent-foreground"
                >
                  {data.index}
                </button>
              );
            }
            if (!citation) return <span>{children}</span>;
            return (
              <button
                type="button"
                aria-label={`查看引用 ${citation.index}`}
                onClick={() => onCite(citation)}
                className="mx-0.5 rounded-sm bg-accent px-1 align-super text-xs text-accent-foreground"
              >
                {citation.index}
              </button>
            );
          },
          img: ({ alt }) => <span>{alt}</span>,
          table: ({ children }) => <Table className="text-sm">{children}</Table>,
          thead: ({ children }) => <TableHeader>{children}</TableHeader>,
          tbody: ({ children }) => <TableBody>{children}</TableBody>,
          tr: ({ children }) => <TableRow>{children}</TableRow>,
          th: ({ children }) => <TableHead>{children}</TableHead>,
          td: ({ children }) => <TableCell className="whitespace-normal">{children}</TableCell>,
          ol: ({ children }) => <ol className="list-decimal pl-6">{children}</ol>,
          ul: ({ children }) => <ul className="list-disc pl-6">{children}</ul>,
        }}
      >
        {withCitationLinks(text, new Set([...byIndex.keys(), ...dataByIndex.keys()]))}
      </ReactMarkdown>
    </div>
  );
}
