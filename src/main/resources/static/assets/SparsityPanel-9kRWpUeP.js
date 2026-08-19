import{r as m,j as e}from"./react-vendor-Ah1pgjPn.js";import{a as N}from"./index-2T2Av26K.js";import{A as w,m as u}from"./framer-motion-hZ45UV5q.js";const C={type:"spring",stiffness:300,damping:28};function W({envelope:a,onApplyOptimized:s}){const[t,p]=m.useState(null),[l,o]=m.useState(!1),[n,x]=m.useState(null),[c,g]=m.useState({leafWeight:.01,depthWeight:.005,coverageFloor:.95});if(a.ruleType!=="DecisionTree")return e.jsxs("div",{className:`rounded-xl border p-4 text-xs
        dark:bg-surface-1 dark:border-border dark:text-text-tertiary
        bg-white border-light-border text-light-text-tertiary`,children:["v2 sparsity 優化僅適用 DecisionTree 型態。目前是 ",a.ruleType,"。"]});const j=async()=>{o(!0),x(null);try{const r=await N.optimizeV2(a,c);p(r)}catch(r){x(typeof r=="object"&&r&&"message"in r?String(r.message):String(r))}finally{o(!1)}},v=()=>{t?.optimized&&s&&s(t.optimized)},h=t?t.sparsityScoreAfter<t.sparsityScoreBefore-1e-9:!1,k=t?t.metricsAfter.leafCount<t.metricsBefore.leafCount:!1;return e.jsxs("div",{className:`
      rounded-xl border overflow-hidden
      dark:bg-surface-1 dark:border-border
      bg-white border-light-border
    `,children:[e.jsxs("div",{className:`flex items-center justify-between px-5 py-3 border-b
        dark:border-border border-light-border`,children:[e.jsxs("div",{className:"flex items-center gap-2.5",children:[e.jsx("div",{className:`
            w-7 h-7 rounded-lg flex items-center justify-center
            dark:bg-violet-900/30 bg-violet-50
            dark:text-violet-300 text-violet-600
          `,children:e.jsxs("svg",{width:"14",height:"14",viewBox:"0 0 16 16",fill:"none",stroke:"currentColor",strokeWidth:"1.5",strokeLinecap:"round",strokeLinejoin:"round",children:[e.jsx("path",{d:"M8 2v3m0 6v3m-6-6h3m6 0h3"}),e.jsx("circle",{cx:"8",cy:"8",r:"2"})]})}),e.jsxs("div",{children:[e.jsx("h3",{className:`text-sm font-semibold
              dark:text-text-primary text-light-text-primary`,children:"規則樹精簡建議"}),e.jsx("p",{className:"text-[11px] dark:text-text-tertiary text-light-text-tertiary",children:"在不影響覆蓋率的前提下，自動把決策樹改得更短、更易讀"})]})]}),e.jsx("button",{onClick:j,disabled:l,className:`
            text-[11px] px-3 py-1.5 rounded-md font-medium transition-all
            dark:bg-violet-700/40 dark:hover:bg-violet-700/60 dark:text-violet-100
            bg-violet-100 hover:bg-violet-200 text-violet-700
            disabled:opacity-50 disabled:cursor-not-allowed
          `,children:l?"優化中…":t?"重新優化":"開始優化"})]}),e.jsxs("details",{className:"px-5 py-3 border-b dark:border-border border-light-border",children:[e.jsx("summary",{className:`text-[11px] cursor-pointer
          dark:text-text-tertiary text-light-text-tertiary
          hover:dark:text-text-secondary hover:text-light-text-secondary`,children:"進階參數"}),e.jsxs("div",{className:"grid grid-cols-3 gap-3 mt-3",children:[e.jsx(b,{label:"偏好少分支",value:c.leafWeight??.01,onChange:r=>g(i=>({...i,leafWeight:r})),step:.005,min:0,max:1,hint:"數值越大，越偏好分支較少的樹"}),e.jsx(b,{label:"偏好淺結構",value:c.depthWeight??.005,onChange:r=>g(i=>({...i,depthWeight:r})),step:.005,min:0,max:1,hint:"數值越大，越偏好層數較淺的樹"}),e.jsx(b,{label:"最低覆蓋率",value:c.coverageFloor??.95,onChange:r=>g(i=>({...i,coverageFloor:r})),step:.05,min:0,max:1,hint:"優化後的樹覆蓋率不低於此值"})]})]}),e.jsx("div",{className:"p-5",children:e.jsxs(w,{mode:"wait",children:[n&&e.jsxs(u.div,{initial:{opacity:0},animate:{opacity:1},className:"text-xs dark:text-danger text-red-600",children:["優化失敗：",n]},"err"),!t&&!l&&!n&&e.jsx("p",{className:"text-xs dark:text-text-tertiary text-light-text-tertiary",children:"點「開始優化」分析此決策樹是否能進一步精簡。"},"hint"),l&&e.jsxs(u.div,{initial:{opacity:0},animate:{opacity:1},className:"space-y-3",children:[e.jsx("div",{className:`h-4 rounded animate-pulse w-1/2
                dark:bg-surface-3 bg-light-surface-3`}),e.jsx("div",{className:`h-4 rounded animate-pulse w-3/4
                dark:bg-surface-3 bg-light-surface-3`}),e.jsx("div",{className:`h-4 rounded animate-pulse w-2/3
                dark:bg-surface-3 bg-light-surface-3`})]},"loading"),t&&!l&&e.jsxs(u.div,{initial:{opacity:0,y:8},animate:{opacity:1,y:0},transition:C,className:"space-y-4",children:[e.jsx("div",{className:`
                rounded-lg px-4 py-3 text-sm
                ${h?"dark:bg-success/10 bg-emerald-50 dark:text-success text-emerald-700":"dark:bg-surface-2 bg-light-surface-2 dark:text-text-secondary text-light-text-secondary"}
              `,children:h?e.jsxs(e.Fragment,{children:["已優化：分數從 ",e.jsx("b",{children:t.sparsityScoreBefore.toFixed(3)})," 降至 ",e.jsx("b",{children:t.sparsityScoreAfter.toFixed(3)}),k&&e.jsxs(e.Fragment,{children:["，葉節點 ",t.metricsBefore.leafCount," → ",t.metricsAfter.leafCount]})]}):e.jsx(e.Fragment,{children:"樹結構已接近最優，本輪未發現更稀疏化空間。"})}),e.jsxs("div",{className:"grid grid-cols-2 gap-3",children:[e.jsx(f,{title:"優化前",m:t.metricsBefore}),e.jsx(f,{title:"優化後",m:t.metricsAfter,highlight:h})]}),Object.keys(t.passContributions??{}).length>0&&e.jsx(y,{title:"各階段貢獻",children:e.jsx("table",{className:"text-xs w-full",children:e.jsx("tbody",{children:Object.entries(t.passContributions).map(([r,i])=>e.jsxs("tr",{className:"border-b dark:border-border/50 border-light-border/50 last:border-0",children:[e.jsx("td",{className:"py-1.5 dark:text-text-tertiary text-light-text-tertiary",children:S[r]??r}),e.jsxs("td",{className:`py-1.5 text-right font-mono
                            dark:text-text-primary text-light-text-primary`,children:["減少 ",i," 個葉節點"]})]},r))})})}),t.appliedOptimizations.length>0&&e.jsx(y,{title:"實際動作",children:e.jsx("ul",{className:"space-y-1",children:t.appliedOptimizations.map((r,i)=>e.jsxs("li",{className:`flex gap-2 text-xs
                        dark:text-text-secondary text-light-text-secondary`,children:[e.jsx("span",{className:"text-violet-500 shrink-0",children:"›"}),e.jsx("span",{children:r})]},i))})}),h&&s&&e.jsx("button",{onClick:v,className:`
                    w-full text-xs px-3 py-2 rounded-md font-medium transition-all
                    dark:bg-violet-700 dark:hover:bg-violet-600 dark:text-white
                    bg-violet-600 hover:bg-violet-700 text-white
                  `,children:"套用優化版本"}),e.jsxs("p",{className:`text-[10px] text-center
                dark:text-text-tertiary text-light-text-tertiary`,children:["耗時 ",t.durationMs," ms · 保證：覆蓋率不會降低、規則數量不會增加"]})]},"result")]})})]})}function f({title:a,m:s,highlight:t}){return e.jsxs("div",{className:`
      rounded-lg border p-3
      ${t?"dark:border-success/40 dark:bg-success/5 border-emerald-300 bg-emerald-50/50":"dark:border-border dark:bg-surface-2 border-light-border bg-light-surface-2"}
    `,children:[e.jsx("p",{className:`text-[10px] uppercase tracking-[0.08em] mb-2 font-semibold
        dark:text-text-tertiary text-light-text-tertiary`,children:a}),e.jsxs("div",{className:"space-y-1.5 text-xs",children:[e.jsx(d,{label:"葉節點",value:s.leafCount}),e.jsx(d,{label:"最大深度",value:s.maxDepth}),e.jsx(d,{label:"平均路徑",value:s.avgPathLength.toFixed(2)}),e.jsx(d,{label:"平衡指數",value:`${(s.balanceIndex*100).toFixed(0)}%`}),e.jsx(d,{label:"重複率",value:`${(s.duplicationRatio*100).toFixed(0)}%`})]})]})}function d({label:a,value:s}){return e.jsxs("div",{className:"flex items-center justify-between",children:[e.jsx("span",{className:"dark:text-text-tertiary text-light-text-tertiary",children:a}),e.jsx("span",{className:`font-mono font-medium
        dark:text-text-primary text-light-text-primary`,children:s})]})}function y({title:a,children:s}){return e.jsxs("div",{children:[e.jsx("h4",{className:`text-[10px] uppercase tracking-[0.08em] font-semibold mb-2
        dark:text-text-primary text-light-text-primary`,children:a}),s]})}function b({label:a,value:s,onChange:t,step:p,min:l,max:o,hint:n}){return e.jsxs("label",{className:`flex flex-col gap-1 text-[11px]
      dark:text-text-tertiary text-light-text-tertiary`,children:[e.jsx("span",{className:"font-medium",children:a}),e.jsx("input",{type:"number",value:s,step:p,min:l,max:o,onChange:x=>t(parseFloat(x.target.value)||0),className:`
          px-2 py-1 rounded border text-xs font-mono
          dark:bg-surface-2 dark:border-border dark:text-text-primary
          bg-white border-light-border text-light-text-primary
          focus:outline-none focus:ring-2 focus:ring-violet-500/40
        `}),n&&e.jsx("span",{className:"text-[10px] dark:text-text-tertiary text-light-text-tertiary",children:n})]})}const S={"pass1-3":"合併重複結果、移除無法到達的分支",pass4:"合併結構相同的子樹",pass5:"化簡冗餘條件",pass6:"重新組合更佳的分支順序"};export{W as default};
