/**
 * 登录页插图（D-042），画出产品的三条核心价值：回答里的引用编号连着原文资料（有出处可核对）、盾牌（按授权作答）、
 * “没有找到依据”（答不了就直说）。只用 tokens.css 的颜色，不含行业内容。
 */
export function TrustIllustration({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 420 300"
      role="img"
      aria-label="回答里的引用编号连着原文资料；按授权作答；找不到依据时直接说明"
      className={className}
    >
      {/* 连线：引用编号 → 原文资料 */}
      <path
        d="M214 82 C 250 82, 254 58, 290 58"
        className="fill-none stroke-brand"
        strokeWidth="2"
        strokeDasharray="5 5"
      />
      <path
        d="M178 118 C 240 118, 250 146, 290 146"
        className="fill-none stroke-brand"
        strokeWidth="2"
        strokeDasharray="5 5"
      />

      {/* 回答气泡 */}
      <rect x="20" y="32" width="230" height="124" rx="18" className="fill-card stroke-border" strokeWidth="2" />
      <circle cx="44" cy="56" r="10" className="fill-accent" />
      <rect x="62" y="51" width="60" height="10" rx="5" className="fill-muted" />
      <rect x="36" y="76" width="160" height="10" rx="5" className="fill-muted" />
      <Citation x={202} y={74} label="1" />
      <rect x="36" y="96" width="190" height="10" rx="5" className="fill-muted" />
      <rect x="36" y="114" width="124" height="10" rx="5" className="fill-muted" />
      <Citation x={166} y={110} label="2" />
      <rect x="36" y="134" width="96" height="10" rx="5" className="fill-muted" />

      {/* 原文资料 */}
      <Source y={26} label="1" />
      <Source y={114} label="2" />

      {/* 授权盾牌 */}
      <g transform="translate(36 178)">
        <path d="M40 0 L76 12 V44 C76 68 60 84 40 92 C20 84 4 68 4 44 V12 Z" className="fill-primary" />
        <path
          d="M24 46 L36 58 L58 32"
          className="fill-none stroke-primary-foreground"
          strokeWidth="6"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </g>

      {/* 找不到就直说 */}
      <rect x="142" y="206" width="250" height="58" rx="16" className="fill-accent" />
      <circle cx="172" cy="232" r="9" className="fill-none stroke-accent-foreground" strokeWidth="3" />
      <path d="M179 239 L186 246" className="stroke-accent-foreground" strokeWidth="3" strokeLinecap="round" />
      <text x="198" y="240" className="fill-accent-foreground" fontSize="17" fontFamily="var(--font-sans)">
        资料里没有找到依据
      </text>
    </svg>
  );
}

function Citation({ x, y, label }: { x: number; y: number; label: string }) {
  return (
    <g>
      <rect x={x} y={y} width="22" height="16" rx="5" className="fill-brand" />
      <text
        x={x + 11}
        y={y + 12.5}
        textAnchor="middle"
        fontSize="12"
        fontWeight="600"
        fontFamily="var(--font-sans)"
        className="fill-primary-foreground"
      >
        {label}
      </text>
    </g>
  );
}

function Source({ y, label }: { y: number; label: string }) {
  return (
    <g transform={`translate(290 ${y})`}>
      <rect width="110" height="72" rx="12" className="fill-card stroke-border" strokeWidth="2" />
      <path d="M0 12 A12 12 0 0 1 12 0 H98 A12 12 0 0 1 110 12 V20 H0 Z" className="fill-secondary" />
      <text x="12" y="15" fontSize="11" fontWeight="600" fontFamily="var(--font-sans)" className="fill-brand">
        [{label}]
      </text>
      <rect x="12" y="32" width="78" height="8" rx="4" className="fill-muted" />
      <rect x="12" y="46" width="60" height="8" rx="4" className="fill-muted" />
    </g>
  );
}
