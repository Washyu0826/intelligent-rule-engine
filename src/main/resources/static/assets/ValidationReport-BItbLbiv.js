import{r as m,j as e}from"./react-vendor-Ah1pgjPn.js";import{e as u,S as g,u as f,E as j,b as y}from"./index-qffVau8y.js";import{m as x,A as v}from"./framer-motion-hZ45UV5q.js";const h={type:"spring",stiffness:300,damping:30};function k(n){const o=n.message.match(/\b(R\d{1,3})\b/);return o?o[1]:null}const b={critical:{bg:"bg-red-500/10",text:"text-red-400",border:"border-red-500/20",bar:"bg-red-400"},warning:{bg:"bg-amber-500/10",text:"text-amber-400",border:"border-amber-500/20",bar:"bg-amber-400"},info:{bg:"bg-blue-500/10",text:"text-blue-400",border:"border-blue-500/20",bar:"bg-blue-400"}},N={critical:"嚴重",warning:"警告",info:"資訊"};function w({errors:n}){const o=m.useMemo(()=>{const a=new Map;for(const t of n){const s=a.get(t.code);s?s.count++:a.set(t.code,{count:1,severity:u(t.code)})}return Array.from(a.entries()).sort((t,s)=>g[t[1].severity]-g[s[1].severity])},[n]),c=Math.max(...o.map(([,a])=>a.count),1),[l,d]=m.useState(null);return e.jsx("div",{className:"flex items-end gap-[3px] h-5",children:o.map(([a,{count:t,severity:s}])=>{const i=b[s],r=t/c*100,p=l===a;return e.jsxs("div",{className:"relative",onMouseEnter:()=>d(a),onMouseLeave:()=>d(null),children:[e.jsx(x.div,{initial:{height:0},animate:{height:`${Math.max(r,15)}%`},transition:h,className:`w-[6px] rounded-full ${i.bar} ${p?"opacity-100":"opacity-60"}`,style:{minHeight:3}}),p&&e.jsxs("div",{className:`
                absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5
                px-2 py-1 rounded-md text-[9px] font-mono whitespace-nowrap z-50
                dark:bg-surface-3 bg-white shadow-lg border dark:border-border border-light-border
                dark:text-text-primary text-light-text-primary
              `,children:[a,": ",t]})]},a)})})}function $({error:n,index:o}){const{dispatch:c,scrollToRef:l}=f(),d=u(n.code),a=b[d],t=k(n),s=j[n.code],i=m.useCallback(()=>{t&&(c(y.navigateAndHighlight("rules",[t],"validation",`rule-${t}`)),setTimeout(()=>l(`rule-${t}`),300))},[t,c,l]);return e.jsxs(x.div,{initial:{opacity:0,y:6},animate:{opacity:1,y:0},transition:{...h,delay:o*.03},className:`
        rounded-lg
        dark:hover:bg-surface-2/50 hover:bg-light-surface-2/50
        transition-colors duration-150
      `,children:[e.jsxs("div",{className:"flex items-start gap-3 px-3 py-2.5",children:[e.jsx("span",{className:`
          text-[10px] font-mono tabular-nums pt-0.5 shrink-0
          dark:text-text-tertiary text-light-text-tertiary
        `,children:String(o+1).padStart(2,"0")}),e.jsx("span",{className:`w-1.5 h-1.5 rounded-full mt-1.5 shrink-0 ${a.bar}`}),e.jsx("code",{className:`
          shrink-0 px-2 py-0.5 rounded text-[10px] font-mono font-medium
          ${a.bg} ${a.text} border ${a.border}
        `,children:n.code}),e.jsx("span",{className:`
          flex-1 text-sm leading-relaxed
          dark:text-text-secondary text-light-text-secondary
        `,children:n.message}),t&&e.jsxs("button",{onClick:r=>{r.stopPropagation(),i()},className:`
              shrink-0 inline-flex items-center gap-1 text-[10px] font-mono
              px-2 py-0.5 rounded cursor-pointer
              dark:bg-surface-3 dark:text-text-tertiary dark:hover:text-accent
              bg-light-surface-3 text-light-text-tertiary hover:text-accent
              transition-colors duration-150
            `,title:`定位到規則 ${t}`,children:[e.jsxs("svg",{width:"10",height:"10",viewBox:"0 0 10 10",fill:"none",stroke:"currentColor",strokeWidth:"1.2",strokeLinecap:"round",strokeLinejoin:"round",children:[e.jsx("path",{d:"M4 6l2-2"}),e.jsx("path",{d:"M3 7a2 2 0 0 1 0-2.83l.7-.7"}),e.jsx("path",{d:"M7 3a2 2 0 0 1 0 2.83l-.7.7"})]}),t]})]}),n.witness&&Object.keys(n.witness).length>0&&e.jsxs("div",{className:"flex items-start gap-2 px-3 pb-2 pl-[52px]",children:[e.jsx("span",{className:`
            text-[10px] shrink-0 mt-0.5 uppercase tracking-wide
            dark:text-text-tertiary text-light-text-tertiary
          `,children:"觸發範例"}),e.jsx("div",{className:"flex flex-wrap gap-1.5",children:Object.entries(n.witness).map(([r,p])=>e.jsxs("span",{className:`
                  inline-flex items-baseline gap-1 text-[10px] font-mono
                  px-1.5 py-0.5 rounded
                  dark:bg-surface-3 dark:text-text-secondary
                  bg-light-surface-3 text-light-text-secondary
                  border dark:border-border/40 border-light-border/40
                `,title:`${r} = ${p}`,children:[e.jsx("span",{className:"dark:text-text-tertiary text-light-text-tertiary",children:r}),e.jsx("span",{className:"opacity-50",children:"="}),e.jsx("span",{className:"text-accent",children:p})]},r))})]}),s&&e.jsxs("div",{className:"flex items-start gap-2 px-3 pb-2.5 pl-[52px]",children:[e.jsx("span",{className:`
            w-4 h-4 rounded-full shrink-0 mt-0.5
            flex items-center justify-center
            bg-accent/10 text-accent
          `,children:e.jsxs("svg",{width:"8",height:"8",viewBox:"0 0 8 8",fill:"currentColor",children:[e.jsx("circle",{cx:"4",cy:"2",r:"0.8"}),e.jsx("rect",{x:"3.2",y:"3.2",width:"1.6",height:"3.2",rx:"0.4"})]})}),e.jsx("p",{className:"text-[11px] leading-relaxed dark:text-text-tertiary text-light-text-tertiary",children:s})]})]})}function C({validation:n}){const[o,c]=m.useState(!0),l=m.useMemo(()=>n.errors?[...n.errors].sort((t,s)=>{const i=u(t.code),r=u(s.code);return g[i]-g[r]}):[],[n.errors]),d=m.useMemo(()=>{const t=[],s=new Map;for(const i of l){const r=u(i.code);s.set(r,(s.get(r)??0)+1)}for(const i of["critical","warning","info"]){const r=s.get(i)??0;r>0&&t.push({severity:i,label:N[i],count:r})}return t},[l]);if(n.valid)return e.jsx("div",{className:`
        rounded-xl border overflow-hidden
        dark:border-success/20 border-success/20
      `,children:e.jsxs("div",{className:`
          relative px-5 py-4
          bg-gradient-to-r from-success/10 via-success/5 to-transparent
        `,children:[e.jsx("div",{className:"absolute inset-0 overflow-hidden",children:e.jsx("div",{className:"absolute inset-0 opacity-30",style:{background:"linear-gradient(90deg, transparent, rgba(16,185,129,0.08), transparent)",animation:"shimmer 3s ease-in-out infinite"}})}),e.jsxs("div",{className:"relative flex items-center gap-3",children:[e.jsx(x.div,{initial:{scale:0},animate:{scale:1},transition:h,className:`
                w-8 h-8 rounded-full bg-success/15
                flex items-center justify-center text-success
              `,children:e.jsx("svg",{width:"18",height:"18",viewBox:"0 0 18 18",fill:"none",stroke:"currentColor",strokeWidth:"2",strokeLinecap:"round",strokeLinejoin:"round",children:e.jsx("path",{d:"M4 9.5l3.5 3.5L14 5"})})}),e.jsxs(x.div,{initial:{opacity:0,x:-8},animate:{opacity:1,x:0},transition:{...h,delay:.1},children:[e.jsx("p",{className:"text-sm font-semibold text-success",children:"驗證通過"}),e.jsx("p",{className:"text-xs dark:text-text-tertiary text-light-text-tertiary mt-0.5",children:"規則格式完整、所有欄位設定正確，且無邏輯矛盾"})]})]})]})});const a=l.length;return e.jsxs("div",{className:`
      rounded-xl border overflow-hidden
      dark:border-danger/20 border-danger/20
    `,children:[e.jsxs("button",{onClick:()=>c(!o),className:`
          w-full px-5 py-4 flex items-center justify-between cursor-pointer
          bg-gradient-to-r from-danger/10 via-danger/5 to-transparent
          hover:from-danger/15 hover:via-danger/8 transition-colors
        `,children:[e.jsxs("div",{className:"flex items-center gap-3",children:[e.jsx("div",{className:`
            w-8 h-8 rounded-full bg-danger/15
            flex items-center justify-center text-danger
          `,children:e.jsx("svg",{width:"18",height:"18",viewBox:"0 0 18 18",fill:"none",stroke:"currentColor",strokeWidth:"2",strokeLinecap:"round",strokeLinejoin:"round",children:e.jsx("path",{d:"M5 5l8 8M13 5l-8 8"})})}),e.jsxs("div",{className:"text-left",children:[e.jsx("p",{className:"text-sm font-semibold text-danger",children:"驗證失敗"}),e.jsxs("p",{className:"text-xs dark:text-text-tertiary text-light-text-tertiary mt-0.5",children:["發現 ",a," 個錯誤，請修正後重新驗證"]})]})]}),e.jsxs("div",{className:"flex items-center gap-3",children:[e.jsx(w,{errors:l}),e.jsx("span",{className:`
            text-[11px] font-mono font-semibold tabular-nums
            px-2 py-0.5 rounded-full
            bg-danger/15 text-danger
          `,children:a}),e.jsx(x.svg,{width:"12",height:"12",viewBox:"0 0 12 12",fill:"none",stroke:"currentColor",strokeWidth:"1.5",strokeLinecap:"round",strokeLinejoin:"round",animate:{rotate:o?180:0},transition:h,children:e.jsx("path",{d:"M3 4.5l3 3 3-3"})})]})]}),e.jsx(v,{children:o&&e.jsx(x.div,{initial:{height:0,opacity:0},animate:{height:"auto",opacity:1},exit:{height:0,opacity:0},transition:h,className:"overflow-hidden",children:e.jsxs("div",{className:`
              px-4 py-3 space-y-4
              dark:bg-surface-1 bg-white
            `,children:[e.jsx("div",{className:"flex flex-wrap gap-2",children:d.map(({severity:t,label:s,count:i})=>{const r=b[t];return e.jsxs("span",{className:`
                        inline-flex items-center gap-1.5 text-[10px] font-medium px-2 py-0.5 rounded
                        ${r.bg} ${r.text}
                      `,children:[e.jsx("span",{className:`w-1.5 h-1.5 rounded-full ${r.bar}`}),s,e.jsxs("span",{className:"font-mono",children:["×",i]})]},t)})}),e.jsx("div",{className:"space-y-1",children:l.map((t,s)=>e.jsx($,{error:t,index:s},`${t.code}-${s}`))})]})})})]})}export{C as default};
