import{r as x,j as e}from"./react-vendor-Ah1pgjPn.js";import{u as g,b as u}from"./index-BFarUSVg.js";import{m as n,A as f}from"./framer-motion-hZ45UV5q.js";const a={type:"spring",stiffness:300,damping:30},h=()=>e.jsxs("svg",{width:"14",height:"14",viewBox:"0 0 14 14",fill:"none",stroke:"currentColor",strokeWidth:"1.4",strokeLinecap:"round",strokeLinejoin:"round",children:[e.jsx("path",{d:"M2 3h4M2 7h4M2 11h4"}),e.jsx("path",{d:"M8 3v8"}),e.jsx("path",{d:"M6 5l2-2 2 2"}),e.jsx("path",{d:"M6 9l2 2 2-2"})]});function b({hint:o,index:s,onClickRuleId:l}){return e.jsxs(n.div,{initial:{opacity:0,y:6},animate:{opacity:1,y:0},transition:{...a,delay:s*.03},className:`
        flex items-start gap-3 px-3 py-3 rounded-lg
        dark:bg-surface-2/50 bg-light-surface-2/50
        dark:hover:bg-surface-2 hover:bg-light-surface-2
        transition-colors duration-150
      `,children:[e.jsx("span",{className:`
        w-5 h-5 rounded-md flex items-center justify-center shrink-0 mt-0.5
        bg-violet-500/10 text-violet-400 text-[10px] font-mono font-semibold
      `,children:s+1}),e.jsxs("div",{className:"flex-1 min-w-0 space-y-2",children:[e.jsxs("div",{className:"flex flex-wrap items-center gap-1.5",children:[o.ruleIds.map((i,c)=>e.jsxs("span",{className:"contents",children:[c>0&&e.jsx("span",{className:"text-[10px] dark:text-text-tertiary/50 text-light-text-tertiary/50",children:"+"}),e.jsx("button",{onClick:t=>{t.stopPropagation(),l(i)},className:`
                  inline-flex items-center px-2 py-0.5 rounded text-[11px] font-mono font-semibold
                  bg-violet-500/12 text-violet-400
                  border border-violet-500/15
                  hover:bg-violet-500/25 hover:border-violet-500/30
                  cursor-pointer transition-colors duration-150
                `,children:i})]},i)),e.jsx("svg",{width:"14",height:"14",viewBox:"0 0 14 14",fill:"none",stroke:"currentColor",strokeWidth:"1.5",strokeLinecap:"round",strokeLinejoin:"round",className:"text-violet-400/50 mx-1",children:e.jsx("path",{d:"M4 7h6M8 5l2 2-2 2"})}),e.jsx("span",{className:`
            inline-flex items-center px-2 py-0.5 rounded text-[11px] font-mono font-semibold
            bg-accent/12 text-accent
            border border-accent/15
          `,children:"合併"})]}),e.jsx("p",{className:"text-[11px] leading-relaxed dark:text-text-secondary text-light-text-secondary",children:o.suggestion})]})]})}function N({analyze:o}){const{dispatch:s,scrollToRef:l}=g(),[i,c]=x.useState(!0),t=o.simplifications??[],p=x.useCallback(r=>{s(u.navigateAndHighlight("rules",[r],"simplification",`rule-${r}`)),setTimeout(()=>l(`rule-${r}`),300)},[s,l]);if(t.length===0)return e.jsx(n.div,{initial:{opacity:0,y:10},animate:{opacity:1,y:0},transition:a,className:`
          rounded-xl border overflow-hidden
          dark:bg-surface-1 dark:border-border bg-white border-light-border
        `,children:e.jsxs("div",{className:"px-5 py-4 flex items-center gap-3",children:[e.jsx("div",{className:"w-7 h-7 rounded-md bg-surface-2 flex items-center justify-center dark:text-text-tertiary text-light-text-tertiary",children:e.jsx(h,{})}),e.jsxs("div",{children:[e.jsx("p",{className:"text-xs font-semibold dark:text-text-secondary text-light-text-secondary",children:"無簡化建議"}),e.jsx("p",{className:"text-[10px] dark:text-text-tertiary text-light-text-tertiary mt-0.5",children:"目前規則已足夠簡潔，無需合併"})]})]})});const m=t.reduce((r,d)=>r+d.ruleIds.length,0)-t.length;return e.jsxs(n.div,{initial:{opacity:0,y:10},animate:{opacity:1,y:0},transition:a,className:`
        rounded-xl border overflow-hidden
        dark:bg-surface-1 dark:border-border bg-white border-light-border
      `,children:[e.jsxs("button",{onClick:()=>c(!i),className:`
          w-full px-4 py-3 flex items-center justify-between cursor-pointer
          hover:dark:bg-surface-2/30 hover:bg-light-surface-2/30 transition-colors
        `,children:[e.jsxs("div",{className:"flex items-center gap-2.5",children:[e.jsx("div",{className:"w-7 h-7 rounded-md bg-violet-500/10 flex items-center justify-center text-violet-400",children:e.jsx(h,{})}),e.jsxs("div",{className:"text-left",children:[e.jsx("p",{className:"text-xs font-semibold dark:text-text-primary text-light-text-primary",children:"簡化建議"}),e.jsxs("p",{className:"text-[10px] dark:text-text-tertiary text-light-text-tertiary mt-0.5",children:[t.length," 組規則可合併，可減少約 ",m," 條規則"]})]})]}),e.jsxs("div",{className:"flex items-center gap-2",children:[e.jsx("span",{className:`
            text-[11px] font-mono font-semibold tabular-nums
            px-2 py-0.5 rounded-full
            bg-violet-500/15 text-violet-400
          `,children:t.length}),e.jsx(n.svg,{width:"12",height:"12",viewBox:"0 0 12 12",fill:"none",stroke:"currentColor",strokeWidth:"1.5",strokeLinecap:"round",strokeLinejoin:"round",animate:{rotate:i?180:0},transition:a,children:e.jsx("path",{d:"M3 4.5l3 3 3-3"})})]})]}),e.jsx(f,{children:i&&e.jsx(n.div,{initial:{height:0,opacity:0},animate:{height:"auto",opacity:1},exit:{height:0,opacity:0},transition:a,className:"overflow-hidden",children:e.jsx("div",{className:"px-4 pb-3 space-y-1.5",children:t.map((r,d)=>e.jsx(b,{hint:r,index:d,onClickRuleId:p},d))})})})]})}export{N as default};
