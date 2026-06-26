import{r as x,j as e}from"./react-vendor-Ah1pgjPn.js";import{a as v}from"./index-DpNYTjZM.js";import{A as N,m as g}from"./framer-motion-hZ45UV5q.js";const w={type:"spring",stiffness:300,damping:28},L=["claude","gemini","ollama","openai"];function E({envelope:l,description:c,provider:a,autoFetch:s=!0}){const[t,f]=x.useState(null),[i,u]=x.useState(!1),[p,y]=x.useState(null),[d,k]=x.useState(a),h=x.useCallback(async r=>{const o=r??a;u(!0),y(null),k(o);try{const n=await v.narrate(c??"",l,o);f(n)}catch(n){y(typeof n=="object"&&n&&"message"in n?String(n.message):String(n))}finally{u(!1)}},[c,l,a]);x.useEffect(()=>{s&&h()},[s]);const j=t?.provider==="fallback";return e.jsxs("div",{className:`
        rounded-xl border overflow-hidden
        dark:bg-surface-1 dark:border-border
        bg-gradient-to-br from-blue-50 to-white
        border-light-border
      `,children:[e.jsxs("div",{className:`flex items-center justify-between px-5 py-3 border-b
        dark:border-border border-light-border
        dark:bg-surface-2 bg-white/50`,children:[e.jsxs("div",{className:"flex items-center gap-2.5",children:[e.jsx("div",{className:`
            w-7 h-7 rounded-lg flex items-center justify-center
            dark:bg-surface-3 bg-blue-100
            dark:text-blue-400 text-blue-600
          `,children:e.jsxs("svg",{width:"14",height:"14",viewBox:"0 0 16 16",fill:"none",stroke:"currentColor",strokeWidth:"1.5",strokeLinecap:"round",strokeLinejoin:"round",children:[e.jsx("path",{d:"M2 3h12M2 7h12M2 11h8"}),e.jsx("circle",{cx:"13",cy:"11",r:"2"})]})}),e.jsxs("div",{children:[e.jsx("h3",{className:`text-sm font-semibold
              dark:text-text-primary text-light-text-primary`,children:"業務總覽"}),e.jsx("p",{className:"text-[11px] dark:text-text-tertiary text-light-text-tertiary",children:"給業務與精算師看的人話版摘要"})]})]}),e.jsxs("div",{className:"flex items-center gap-2",children:[t?.provider&&!j&&e.jsx("span",{className:`text-[10px] px-2 py-1 rounded-md
              dark:bg-surface-3 bg-light-surface-3
              dark:text-text-tertiary text-light-text-tertiary`,children:t.provider}),e.jsx("button",{onClick:()=>h(),disabled:i,className:`
              text-[11px] px-3 py-1.5 rounded-md font-medium transition-all
              dark:bg-surface-3 dark:hover:bg-surface-4
              bg-light-surface-3 hover:bg-light-surface-4
              dark:text-text-secondary text-light-text-secondary
              disabled:opacity-50 disabled:cursor-not-allowed
            `,children:i?"產生中…":t?"重新產生":"產生"})]})]}),e.jsx("div",{className:"p-5",children:e.jsxs(N,{mode:"wait",children:[i&&!t&&e.jsx(g.div,{initial:{opacity:0},animate:{opacity:1},exit:{opacity:0},className:"space-y-3",children:[0,1,2].map(r=>e.jsx("div",{className:`h-4 rounded
                  dark:bg-surface-3 bg-light-surface-3
                  animate-pulse`,style:{width:`${80-r*10}%`}},r))},"loading"),p&&e.jsxs(g.div,{initial:{opacity:0},animate:{opacity:1},className:"space-y-2",children:[e.jsxs("p",{className:"text-xs dark:text-danger text-red-600",children:["無法載入業務敘事",d?`（provider=${d}）`:"","：",p]}),e.jsx(b,{lastProvider:d,loading:i,onRetry:r=>h(r)})]},"error"),t&&!i&&e.jsxs(g.div,{initial:{opacity:0,y:8},animate:{opacity:1,y:0},transition:w,className:"space-y-4",children:[j&&e.jsxs("div",{className:`text-[11px] px-3 py-2 rounded-md space-y-2
                  dark:bg-warning/10 bg-amber-50
                  dark:text-warning text-amber-700
                  border dark:border-warning/30 border-amber-200`,children:[e.jsxs("p",{children:["LLM 服務不可用",d?`（provider=${d}）`:"","，以下為結構化摘要；可改用其他 provider 重試"]}),e.jsx(b,{lastProvider:d,loading:i,onRetry:r=>h(r)})]}),t.summary&&e.jsx("p",{className:`text-sm leading-relaxed
                  dark:text-text-primary text-light-text-primary`,children:t.summary}),t.highlights&&t.highlights.length>0&&e.jsx(m,{title:"關鍵要點",icon:"star",children:e.jsx("ul",{className:"space-y-1.5",children:t.highlights.map((r,o)=>e.jsxs("li",{className:`flex gap-2 text-sm
                        dark:text-text-secondary text-light-text-secondary`,children:[e.jsx("span",{className:"text-blue-500 shrink-0",children:"•"}),e.jsx("span",{children:r})]},o))})}),t.coverage&&e.jsx(m,{title:"涵蓋範圍",icon:"target",children:e.jsx("p",{className:"text-sm dark:text-text-secondary text-light-text-secondary",children:t.coverage})}),t.exceptions&&t.exceptions.length>0&&e.jsx(m,{title:"特殊例外",icon:"warn",children:e.jsx("ul",{className:"space-y-1.5",children:t.exceptions.map((r,o)=>e.jsxs("li",{className:`flex gap-2 text-sm
                        dark:text-text-secondary text-light-text-secondary`,children:[e.jsx("span",{className:"text-amber-500 shrink-0",children:"!"}),e.jsx("span",{children:r})]},o))})}),t.actuarialNote&&e.jsx(m,{title:"精算師備註",icon:"note",subdued:!0,children:e.jsx("p",{className:`text-xs italic
                    dark:text-text-tertiary text-light-text-tertiary`,children:t.actuarialNote})})]},"content"),!t&&!i&&!p&&e.jsx("p",{className:"text-xs dark:text-text-tertiary text-light-text-tertiary",children:"點「產生」可由 LLM 撰寫業務口吻的規則總覽（本地 Ollama 約需 1–2 分鐘；雲端 provider 數秒）。"})]})})]})}function m({title:l,icon:c,subdued:a,children:s}){const t=C[c];return e.jsxs("div",{children:[e.jsxs("div",{className:"flex items-center gap-1.5 mb-2",children:[e.jsx("span",{className:`
          inline-flex items-center justify-center w-4 h-4 rounded
          ${a?"dark:text-text-tertiary text-light-text-tertiary":"dark:text-blue-400 text-blue-500"}
        `,children:t}),e.jsx("h4",{className:`
          text-[11px] uppercase tracking-[0.08em] font-semibold
          ${a?"dark:text-text-tertiary text-light-text-tertiary":"dark:text-text-primary text-light-text-primary"}
        `,children:l})]}),s]})}const C={star:e.jsx("svg",{width:"11",height:"11",viewBox:"0 0 12 12",fill:"none",stroke:"currentColor",strokeWidth:"1.5",strokeLinecap:"round",strokeLinejoin:"round",children:e.jsx("path",{d:"M6 1l1.5 3.2L11 4.7l-2.5 2.4L9 10.5 6 8.9 3 10.5l.5-3.4L1 4.7l3.5-.5z"})}),target:e.jsxs("svg",{width:"11",height:"11",viewBox:"0 0 12 12",fill:"none",stroke:"currentColor",strokeWidth:"1.5",strokeLinecap:"round",strokeLinejoin:"round",children:[e.jsx("circle",{cx:"6",cy:"6",r:"5"}),e.jsx("circle",{cx:"6",cy:"6",r:"2.5"}),e.jsx("circle",{cx:"6",cy:"6",r:"0.5",fill:"currentColor"})]}),warn:e.jsxs("svg",{width:"11",height:"11",viewBox:"0 0 12 12",fill:"none",stroke:"currentColor",strokeWidth:"1.5",strokeLinecap:"round",strokeLinejoin:"round",children:[e.jsx("path",{d:"M6 1l5 9H1z"}),e.jsx("path",{d:"M6 5v2.5"}),e.jsx("circle",{cx:"6",cy:"9",r:"0.3",fill:"currentColor"})]}),note:e.jsxs("svg",{width:"11",height:"11",viewBox:"0 0 12 12",fill:"none",stroke:"currentColor",strokeWidth:"1.5",strokeLinecap:"round",strokeLinejoin:"round",children:[e.jsx("rect",{x:"2",y:"1.5",width:"8",height:"9",rx:"1"}),e.jsx("path",{d:"M4 4h4M4 6h4M4 8h2"})]})};function b({lastProvider:l,loading:c,onRetry:a}){return e.jsxs("div",{className:"flex flex-wrap gap-1.5",children:[e.jsx("span",{className:"text-[10px] dark:text-text-tertiary text-light-text-tertiary self-center",children:"改用："}),L.filter(s=>s!==l).map(s=>e.jsx("button",{onClick:()=>a(s),disabled:c,className:`
            text-[10px] font-mono px-2 py-1 rounded border transition-colors
            dark:bg-surface-2 dark:border-border dark:text-text-secondary
            dark:hover:bg-surface-3 dark:hover:text-text-primary
            bg-white border-light-border text-light-text-secondary
            hover:bg-light-surface-2 hover:text-light-text-primary
            disabled:opacity-50 disabled:cursor-not-allowed
          `,children:s},s))]})}export{E as default};
