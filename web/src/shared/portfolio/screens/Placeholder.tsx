export function Placeholder({ title }: { title: string }) {
  return (
    <section className="pf-placeholder" aria-label={title}>
      <h1 className="display">{title}</h1>
      <p className="note">Coming in the next task.</p>
    </section>
  );
}
