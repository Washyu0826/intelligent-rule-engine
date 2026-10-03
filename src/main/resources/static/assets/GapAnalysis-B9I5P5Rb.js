import{r as p,j as e}from"./react-vendor-Ah1pgjPn.js";import{u as j,b as v}from"./index-BFarUSVg.js";import{f as k,h as N}from"./fieldLabels-4RCM1_OK.js";import{m as l,A as w}from"./framer-motion-hZ45UV5q.js";const c={type:"spring",stiffness:300,damping:30},C={high:{badge:"bg-danger/15 text-danger",pill:"bg-danger/10 text-danger",label:"高"},mid:{badge:"bg-amber-500/15 text-amber-500",pill:"bg-amber-500/10 text-amber-500",label:"中"},low:{badge:"dark:bg-surface-3 dark:text-text-tertiary bg-light-surface-3 text-light-text-tertiary",pill:"dark:bg-surface-3 dark:text-text-tertiary bg-light-surface-3 text-light-text-tertiary",label:"低"}};function L(t,s){if(!t||s<=0)return"low";const a=t/s;return a>=.66?"high":a>=.33?"mid":"low"}function R(t){if(t==null)return null;const s=t*100;return s<=0?null:s<.1?"<0.1%":`${s.toFixed(s<1?2:1)}%`}const G=()=>e.jsxs("svg",{width:"14",height:"14",viewBox:"0 0 14 14",fill:"none",stroke:"currentColor",strokeWidth:"1.4",strokeLinecap:"round",strokeLinejoin:"round",children:[e.jsx("rect",{x:"1",y:"1",width:"12",height:"12",rx:"2",strokeDasharray:"3 2"}),e.jsx("path",{d:"M5 7h4"})]});function E({gap:t,index:s,maxVol:a,onClickGap:u}){const d=Object.entries(t.conditions??{}),[g,r]=p.useState(!1),f=L(t.volumeRatio,a),o=C[f],h=R(t.volumeRatio);return e.jsxs(l.div,{initial:{opacity:0,y:6},animate:{opacity:1,y:0},transition:{...c,delay:s*.03},onClick:()=>u(t),onMouseEnter:()=>r(!0),onMouseLeave:()=>r(!1),className:`
        flex items-start gap-3 px-3 py-3 rounded-lg cursor-pointer
        dark:bg-surface-2/50 bg-light-surface-2/50
        dark:hover:bg-surface-2 hover:bg-light-surface-2
        transition-colors duration-150
      `,children:[e.jsx("span",{className:`
        w-5 h-5 rounded-md flex items-center justify-center shrink-0 mt-0.5
        text-[10px] font-mono font-semibold ${o.badge}
      `,children:s+1}),e.jsxs("div",{className:"flex-1 min-w-0 space-y-1.5",children:[e.jsxs("div",{className:"flex items-center gap-1.5",children:[e.jsxs("span",{className:`text-[10px] font-semibold px-1.5 py-0.5 rounded ${o.pill}`,children:["嚴重度 ",o.label]}),h&&e.jsxs("span",{className:"text-[10px] dark:text-text-tertiary text-light-text-tertiary",children:["影響範圍 ",h]})]}),e.jsx("div",{className:"flex flex-wrap gap-1.5",children:d.map(([x,m])=>e.jsxs("span",{title:`${x} = ${m}`,className:`
                inline-flex items-center gap-1 text-[11px]
                px-2 py-0.5 rounded
                bg-red-500/8 text-red-400
                border border-red-500/12
              `,children:[e.jsx("span",{className:"font-semibold",children:k(x)}),e.jsx("span",{className:"opacity-50",children:"="}),e.jsx("span",{className:"font-mono",children:N(m)})]},x))}),t.message&&e.jsx("p",{className:"text-[11px] leading-relaxed dark:text-text-tertiary text-light-text-tertiary",children:t.message}),e.jsx("p",{className:`
          text-[10px] transition-colors duration-150
          ${g?"text-accent":"dark:text-text-tertiary/40 text-light-text-tertiary/40"}
        `,children:"點擊查看熱力圖"})]})]})}function I({analyze:t}){const{dispatch:s}=j(),[a,u]=p.useState(!0),[d,g]=p.useState(!1),r=t.gaps??[],f=r.reduce((i,n)=>Math.max(i,n.volumeRatio??0),0),o=[...r].sort((i,n)=>(n.volumeRatio??0)-(i.volumeRatio??0)),h=p.useCallback(i=>{const n=Object.entries(i.conditions??{}),b=n[0]?.[1]??"",y=n[1]?.[1]??"";s(v.navigateAndHighlightCell("analysis",{x:b,y}))},[s]);if(r.length===0)return e.jsx(l.div,{initial:{opacity:0,y:10},animate:{opacity:1,y:0},transition:c,className:`
          rounded-xl border overflow-hidden
          dark:bg-surface-1 dark:border-success/20 bg-white border-success/20
        `,children:e.jsxs("div",{className:"px-5 py-4 flex items-center gap-3 bg-gradient-to-r from-success/5 to-transparent",children:[e.jsx(l.div,{initial:{scale:0},animate:{scale:1},transition:c,className:"w-7 h-7 rounded-md bg-success/10 flex items-center justify-center text-success",children:e.jsx("svg",{width:"14",height:"14",viewBox:"0 0 14 14",fill:"none",stroke:"currentColor",strokeWidth:"2",strokeLinecap:"round",strokeLinejoin:"round",children:e.jsx("path",{d:"M4 7.5l2.5 2.5L10 5"})})}),e.jsxs("div",{children:[e.jsx("p",{className:"text-xs font-semibold text-success",children:"無覆蓋缺口"}),e.jsx("p",{className:"text-[10px] dark:text-text-tertiary text-light-text-tertiary mt-0.5",children:"所有條件組合均已被規則覆蓋"})]})]})});const x=d?o:o.slice(0,8),m=r.length>8;return e.jsxs(l.div,{initial:{opacity:0,y:10},animate:{opacity:1,y:0},transition:c,className:`
        rounded-xl border overflow-hidden
        dark:bg-surface-1 dark:border-border bg-white border-light-border
      `,children:[e.jsxs("button",{onClick:()=>u(!a),className:`
          w-full px-4 py-3 flex items-center justify-between cursor-pointer
          hover:dark:bg-surface-2/30 hover:bg-light-surface-2/30 transition-colors
        `,children:[e.jsxs("div",{className:"flex items-center gap-2.5",children:[e.jsx("div",{className:"w-7 h-7 rounded-md bg-danger/10 flex items-center justify-center text-danger",children:e.jsx(G,{})}),e.jsxs("div",{className:"text-left",children:[e.jsx("p",{className:"text-xs font-semibold dark:text-text-primary text-light-text-primary",children:"覆蓋缺口分析"}),e.jsxs("p",{className:"text-[10px] dark:text-text-tertiary text-light-text-tertiary mt-0.5",children:["發現 ",r.length," 個未覆蓋的條件組合"]})]})]}),e.jsxs("div",{className:"flex items-center gap-2",children:[e.jsx("span",{className:`
            text-[11px] font-mono font-semibold tabular-nums
            px-2 py-0.5 rounded-full
            bg-danger/15 text-danger
          `,children:r.length}),e.jsx(l.svg,{width:"12",height:"12",viewBox:"0 0 12 12",fill:"none",stroke:"currentColor",strokeWidth:"1.5",strokeLinecap:"round",strokeLinejoin:"round",animate:{rotate:a?180:0},transition:c,children:e.jsx("path",{d:"M3 4.5l3 3 3-3"})})]})]}),e.jsx(w,{children:a&&e.jsx(l.div,{initial:{height:0,opacity:0},animate:{height:"auto",opacity:1},exit:{height:0,opacity:0},transition:c,className:"overflow-hidden",children:e.jsxs("div",{className:"px-4 pb-3 space-y-1.5",children:[x.map((i,n)=>e.jsx(E,{gap:i,index:n,maxVol:f,onClickGap:h},n)),m&&e.jsx("button",{onClick:()=>g(!d),className:`
                    w-full text-center py-2 text-[10px] font-medium cursor-pointer
                    dark:text-text-tertiary dark:hover:text-accent
                    text-light-text-tertiary hover:text-accent
                    transition-colors
                  `,children:d?"收起":`顯示全部 ${r.length} 個缺口`})]})})})]})}export{I as default};
