import { useState } from "react";

/** Delete, with the volumes choice made up front so the confirmation shows exactly what will go. */
export function StackDelete({ onDelete }: { onDelete: (volumes: boolean) => void }) {
  const [open, setOpen] = useState(false);
  if (!open) return <button className="btn sm" onClick={() => setOpen(true)}>Delete…</button>;
  return (
    <span className="dk-del">
      <button className="btn sm danger" onClick={() => { setOpen(false); onDelete(false); }}>Delete (keep volumes)</button>
      <button className="btn sm danger" onClick={() => { setOpen(false); onDelete(true); }}>Delete with volumes</button>
      <button className="btn sm" onClick={() => setOpen(false)}>Cancel</button>
    </span>
  );
}
