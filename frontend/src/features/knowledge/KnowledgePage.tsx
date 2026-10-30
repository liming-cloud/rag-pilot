import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Search } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Link, useNavigate } from "react-router";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Pagination } from "@/components/ui/pagination";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Can } from "@/lib/permissions";
import { createKnowledgeBase, fetchKnowledgeBasePage } from "./api";

const PAGE_SIZE = 20;

/**
 * 知识库列表（D-039）：按名称筛选、分页；每个库显示文档数、失败数和授权情况，点进去是详情（文档、授权）。
 * 新建知识库在弹窗里做。管理员也只看到授权给自己或自己角色的库（D-017）。
 */
export function KnowledgePage() {
  const [filter, setFilter] = useState({ keyword: "", page: 1, size: PAGE_SIZE });
  const [keyword, setKeyword] = useState("");
  const [creating, setCreating] = useState(false);
  const knowledgeBases = useQuery({
    queryKey: ["knowledge-bases", "page", filter],
    queryFn: () => fetchKnowledgeBasePage(filter),
    placeholderData: keepPreviousData,
  });

  function search(event: FormEvent) {
    event.preventDefault();
    setFilter({ ...filter, keyword: keyword.trim(), page: 1 });
  }

  return (
    <main className="mx-auto flex max-w-6xl flex-col gap-4 p-6 md:p-8">
      <div className="flex items-center justify-between">
        <h1 className="font-serif text-2xl font-semibold">知识库</h1>
        <Can permission="kb:create">
          <Button size="sm" onClick={() => setCreating(true)}>
            <Plus aria-hidden="true" className="size-4" />
            新建知识库
          </Button>
        </Can>
      </div>
      <form role="search" aria-label="筛选知识库" onSubmit={search} className="flex items-end gap-2">
        <Label className="flex flex-col gap-1 text-sm">
          名称
          <Input
            value={keyword}
            placeholder="知识库名称"
            className="w-64"
            onChange={(event) => setKeyword(event.target.value)}
          />
        </Label>
        <Button type="submit" size="sm" variant="outline">
          <Search aria-hidden="true" className="size-4" />
          查询
        </Button>
      </form>
      {knowledgeBases.isError && <p className="text-destructive">读取知识库失败，请稍后重试。</p>}
      <div className="rounded-lg border bg-card">
        <Table aria-label="知识库">
          <TableHeader>
            <TableRow>
              <TableHead>名称</TableHead>
              <TableHead>文档</TableHead>
              <TableHead>授权</TableHead>
              <TableHead className="text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {knowledgeBases.data?.items.length === 0 && (
              <TableRow>
                <TableCell colSpan={4} className="py-6 text-center text-muted-foreground">
                  没有符合条件的知识库
                </TableCell>
              </TableRow>
            )}
            {knowledgeBases.data?.items.map((knowledgeBase) => (
              <TableRow key={knowledgeBase.id}>
                <TableCell className="py-3 font-medium">
                  <Link to={`/admin/knowledge/${knowledgeBase.id}`} className="hover:underline">
                    {knowledgeBase.name}
                  </Link>
                </TableCell>
                <TableCell>
                  <span className="flex items-center gap-2">
                    {knowledgeBase.documentCount} 份
                    {knowledgeBase.failedCount > 0 && (
                      <Badge variant="destructive">{knowledgeBase.failedCount} 份失败</Badge>
                    )}
                  </span>
                </TableCell>
                <TableCell className="text-muted-foreground">
                  {knowledgeBase.roleGrantCount} 个角色 · {knowledgeBase.memberCount} 个账号
                </TableCell>
                <TableCell className="text-right">
                  <Button asChild variant="ghost" size="sm">
                    <Link to={`/admin/knowledge/${knowledgeBase.id}`} aria-label={`管理 ${knowledgeBase.name}`}>
                      管理
                    </Link>
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {knowledgeBases.data && (
        <Pagination
          page={knowledgeBases.data.page}
          size={knowledgeBases.data.size}
          total={knowledgeBases.data.total}
          onPageChange={(page) => setFilter({ ...filter, page })}
        />
      )}
      <Dialog open={creating} onOpenChange={setCreating}>
        {creating && <CreateDialog onDone={() => setCreating(false)} />}
      </Dialog>
    </main>
  );
}

function CreateDialog({ onDone }: { onDone: () => void }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const create = useMutation({
    mutationFn: () => createKnowledgeBase(name.trim()),
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: ["knowledge-bases"] });
      onDone();
      navigate(`/admin/knowledge/${created.id}`);
    },
  });
  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>新建知识库</DialogTitle>
        <DialogDescription>建好后进入详情上传文档，并在“授权”里授权给角色或账号。</DialogDescription>
      </DialogHeader>
      <form
        className="flex flex-col gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          if (name.trim()) create.mutate();
        }}
      >
        <Label className="flex flex-col gap-1">
          名称
          <Input required maxLength={50} value={name} onChange={(event) => setName(event.target.value)} />
        </Label>
        {create.isError && <p className="text-sm text-destructive">{create.error.message}</p>}
        <DialogFooter>
          <Button type="button" variant="ghost" size="sm" onClick={onDone}>
            取消
          </Button>
          <Button type="submit" size="sm" disabled={create.isPending}>
            新建
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  );
}
