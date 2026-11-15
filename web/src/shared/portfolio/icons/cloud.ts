import { ci, type IconDef } from "./shape";

const C = "Cloud and data warehouse";
const CLOUD = "M6.5 18a4 4 0 0 1-.4-8 6 6 0 0 1 11.6-1.4A4.5 4.5 0 0 1 17.5 18z";
export const CLOUD_ICONS: Record<string, IconDef> = {
  redshift: { l: "Amazon Redshift", c: C, d: "M2.5 14h7v7h-7z M9.5 14h7v7h-7z M6 7h7v7H6z M16.5 5.5h5 M19.5 3l2.5 2.5-2.5 2.5" },
  spectrum: { l: "Redshift Spectrum", c: C, d: "M3 7c3-3 6 3 9 0s6 3 9 0 M3 12c3-3 6 3 9 0s6 3 9 0 M5 17.5h14 M5 20.5h9" },
  lambda: { l: "AWS Lambda", c: C, d: "M7 4h3.5L17.5 20 M11.3 11.2L6.5 20" },
  s3: { l: "AWS S3 bucket", c: C, d: "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z M4 6l2 13c.2 1.2 2.9 2 6 2s5.8-.8 6-2l2-13" },
  glue: { l: "AWS Glue", c: C, d: "M4 4h16l-6 8v6l-4 2v-8z" },
  secrets: { l: "Secrets Manager", c: C, d: `${ci(8, 9, 3.5)}M10.5 11.5l9 8.5 M15.5 16l-2 2` },
  kms: { l: "KMS keys", c: C, d: "M12 3l8 3v6c0 4.5-3.4 7.7-8 9-4.6-1.3-8-4.5-8-9V6z M12 9.5v5 M12 9.5h.01" },
  iam: { l: "IAM", c: C, d: "M12 3.5a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7z M5 20.5l1.5-5 5.5-1.5 5.5 1.5 1.5 5z" },
  databricks: { l: "Databricks", c: C, d: "M3 8l9 4.5L21 8l-9-4.5z M3 12.5l9 4.5 9-4.5 M3 17l9 4.5 9-4.5" },
  catalog: { l: "Unity Catalog", c: C, d: "M4 4h6v6H4z M4 14h6v6H4z M14 5h6 M14 9h4 M14 15h6 M14 19h4" },
  delta: { l: "Delta Lake", c: C, d: "M12 3.5L21 20H3z M7.5 14.5c3 2 6-2 9 0" },
  sqs: { l: "Amazon SQS queue", c: C, d: "M3 6h5v9H3z M9.5 6h5v9h-5z M16 6h5v9h-5z M3 19h17 M17.5 16.5l2.5 2.5-2.5 2.5" },
  athena: { l: "Amazon Athena", c: C, d: "M14 4.5a5.5 5.5 0 1 1 0 11 5.5 5.5 0 0 1 0-11z M10 14L4 20.5 M11.5 8h5 M11.5 11h3" },
  cloud: { l: "Cloud", c: C, d: CLOUD },
  cloudflared: { l: "Cloudflare Tunnel", c: C, d: `${CLOUD} M9.5 18v-2.5a2.5 2.5 0 0 1 5 0V18` },
};
