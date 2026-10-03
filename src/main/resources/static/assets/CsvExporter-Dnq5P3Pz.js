import{r as d,j as e}from"./react-vendor-Ah1pgjPn.js";import{p as N}from"./index-CO_ZLvro.js";import{m as L}from"./framer-motion-hZ45UV5q.js";const T={type:"spring",stiffness:300,damping:30};function A(t){return t.valueRef?`→ ${t.valueRef}`:t.operator==="anything"?"*":t.operator==="between"&&Array.isArray(t.value)?`[${t.value[0]}, ${t.value[1]}]`:(t.operator==="in"||t.operator==="notIn")&&Array.isArray(t.value)?`{${t.value.join(", ")}}`:t.operator==="isNull"||t.operator==="isNotNull"?t.operator:`${t.operator==="equals"?"":`${t.operator} `}${String(t.value??"")}`}function D(t,r){const u=N(t.rule);if(!u)return{headers:[],rows:[]};const{inputs:i,outputs:n,rules:s}=u,p=[];for(const o of i)p.push(`condition:${o.name}`),r&&(p.push(`condition_op:${o.name}`),p.push(`condition_type:${o.name}`),p.push(`condition_valueRef:${o.name}`),p.push(`fieldCode:${o.name}`));const j=n.map(o=>`result:${o.name}`),g=["ruleId","priority",...p,...j],f=s.map(o=>{const a=[o.ruleId,String(o.priority)];for(const h of i){const x=o.conditions.find(b=>b.field===h.name);x?(a.push(A(x)),r&&(a.push(x.operator),a.push(h.typeRef),a.push(x.valueRef??""),a.push(h.fieldCode??""))):(a.push(""),r&&(a.push(""),a.push(h.typeRef),a.push(""),a.push(h.fieldCode??"")))}for(const h of n){const x=o.results.find(b=>b.field===h.name);a.push(x?String(x.value??""):"")}return a});return{headers:g,rows:f}}function v(t){return t.includes(",")||t.includes('"')||t.includes(`
`)||t.includes("[")?`"${t.replace(/"/g,'""')}"`:t}function M(t,r){return[t.map(v).join(","),...r.map(u=>u.map(v).join(","))].join(`
`)}function B(t,r){const u=new Blob(["\uFEFF"+t],{type:"text/csv;charset=utf-8;"}),i=URL.createObjectURL(u),n=document.createElement("a");n.href=i,n.download=r,document.body.appendChild(n),n.click(),document.body.removeChild(n),URL.revokeObjectURL(i)}function F({value:t,isEditing:r,onStartEdit:u,onCommit:i}){const n=d.useRef(null);return d.useEffect(()=>{r&&n.current&&(n.current.focus(),n.current.select())},[r]),r?e.jsx("input",{ref:n,defaultValue:t,onBlur:s=>i(s.target.value),onKeyDown:s=>{s.key==="Enter"?i(s.target.value):s.key==="Escape"&&i(t)},className:`
          w-full px-1.5 py-0.5 text-[10px] font-mono
          bg-accent/10 text-accent outline-none rounded
          border border-accent/30
        `}):e.jsx("span",{onClick:u,className:`
        block w-full px-1.5 py-0.5 cursor-text rounded
        hover:bg-accent/5 transition-colors
      `,children:t||e.jsx("span",{className:"opacity-30",children:"—"})})}function W({envelope:t}){const[r,u]=d.useState(!1),{headers:i,rows:n}=d.useMemo(()=>D(t,r),[t,r]),[s,p]=d.useState([]),[j,g]=d.useState(null),[f,o]=d.useState(!1),[a,h]=d.useState(!1),[x,b]=d.useState(0);d.useEffect(()=>{p(n.map(l=>[...l])),b(0),g(null)},[n]);const C=d.useMemo(()=>N(t.rule),[t.rule]),R=d.useCallback((l,c,k)=>{p(m=>{const y=m.map(w=>[...w]);return y[l][c]!==k&&(y[l][c]=k,b(w=>w+1)),y}),g(null)},[]),$=d.useCallback(()=>{const l=M(i,s),c=new Date().toISOString().slice(0,10);B(l,`rules-${t.ruleType}-${c}.csv`),o(!0)},[i,s,t.ruleType]);if(d.useEffect(()=>{if(f){const l=setTimeout(()=>o(!1),3e3);return()=>clearTimeout(l)}},[f]),!C||s.length===0)return t.ruleType==="DecisionTree"?e.jsx("div",{className:`rounded-xl border p-4 text-xs
          dark:bg-surface-1 dark:border-border dark:text-text-tertiary
          bg-white border-light-border text-light-text-tertiary`,children:"決策樹結構建議以 RuleEnvelope JSON 交付 adapter；CSV 對照表僅適用於決策表型態。"}):null;const S=a?s:s.slice(0,10),E=s.length>10;return e.jsxs(L.div,{initial:{opacity:0,y:10},animate:{opacity:1,y:0},transition:T,className:`
        rounded-xl border overflow-hidden
        dark:bg-surface-1 dark:border-border bg-white border-light-border
      `,children:[e.jsxs("div",{className:`
        flex items-center justify-between px-4 py-3
        border-b dark:border-border border-light-border
      `,children:[e.jsxs("div",{className:"flex items-center gap-2.5",children:[e.jsx("div",{className:`
            w-7 h-7 rounded-md flex items-center justify-center
            dark:bg-surface-3 bg-light-surface-3
            dark:text-text-tertiary text-light-text-tertiary
          `,children:e.jsxs("svg",{width:"14",height:"14",viewBox:"0 0 14 14",fill:"none",stroke:"currentColor",strokeWidth:"1.3",strokeLinecap:"round",strokeLinejoin:"round",children:[e.jsx("rect",{x:"1.5",y:"1.5",width:"11",height:"11",rx:"1.5"}),e.jsx("line",{x1:"1.5",y1:"5",x2:"12.5",y2:"5"}),e.jsx("line",{x1:"1.5",y1:"8.5",x2:"12.5",y2:"8.5"}),e.jsx("line",{x1:"5.5",y1:"5",x2:"5.5",y2:"12.5"}),e.jsx("line",{x1:"9.5",y1:"5",x2:"9.5",y2:"12.5"})]})}),e.jsxs("div",{children:[e.jsx("p",{className:"text-xs font-semibold dark:text-text-primary text-light-text-primary",children:"規則表 CSV（交付對照）"}),e.jsxs("p",{className:"text-[10px] dark:text-text-tertiary text-light-text-tertiary",children:[s.length," 條規則 · ",i.length," 欄 · ",r?"含 adapter metadata":"簡化檢視"]})]})]}),e.jsxs("div",{className:"flex items-center gap-2",children:[e.jsxs("button",{onClick:()=>u(l=>!l),title:r?"關閉後僅輸出條件值欄；adapter mode 會多附 operator/typeRef/valueRef/fieldCode 以利接入":"開啟後 CSV 會多附 operator、typeRef、valueRef、fieldCode 欄，供下游 adapter round-trip",className:`
              inline-flex items-center gap-1.5 text-[10px] font-medium
              px-2.5 py-1.5 rounded-lg cursor-pointer transition-all duration-200 border
              ${r?"bg-accent/10 text-accent border-accent/30":"dark:bg-surface-3 dark:border-border bg-light-surface-3 border-light-border dark:text-text-tertiary text-light-text-tertiary"}
            `,children:[e.jsx("span",{className:`
              w-3 h-3 rounded-full inline-flex items-center justify-center text-[8px]
              ${r?"bg-accent text-surface-0":"dark:bg-surface-4 bg-light-surface-2"}
            `,children:r?"✓":""}),"Adapter metadata"]}),e.jsx("button",{onClick:$,className:`
            inline-flex items-center gap-1.5 text-[11px] font-medium
            px-3 py-1.5 rounded-lg cursor-pointer transition-all duration-200
            ${f?"bg-success/15 text-success":"bg-accent/10 text-accent hover:bg-accent/20 border border-accent/20"}
          `,children:f?e.jsxs(e.Fragment,{children:[e.jsx("svg",{width:"12",height:"12",viewBox:"0 0 12 12",fill:"none",stroke:"currentColor",strokeWidth:"1.5",strokeLinecap:"round",strokeLinejoin:"round",children:e.jsx("path",{d:"M2.5 6.5l2.5 2.5 4.5-5"})}),"已下載"]}):e.jsxs(e.Fragment,{children:[e.jsxs("svg",{width:"12",height:"12",viewBox:"0 0 12 12",fill:"none",stroke:"currentColor",strokeWidth:"1.4",strokeLinecap:"round",strokeLinejoin:"round",children:[e.jsx("path",{d:"M6 2v6M3.5 6L6 8.5 8.5 6"}),e.jsx("path",{d:"M2 9.5h8"})]}),"下載 CSV"]})})]})]}),e.jsx("div",{className:"overflow-x-auto",children:e.jsxs("table",{className:"w-full border-collapse text-[10px] font-mono",children:[e.jsx("thead",{children:e.jsxs("tr",{children:[e.jsx("th",{className:`
                px-2 py-2 text-right whitespace-nowrap
                dark:bg-surface-2 bg-light-surface-2
                dark:text-text-tertiary/40 text-light-text-tertiary/40
                border-b dark:border-border border-light-border
              `,children:"#"}),i.map((l,c)=>e.jsx("th",{className:`
                    px-2 py-2 text-left whitespace-nowrap
                    dark:bg-surface-2 bg-light-surface-2
                    dark:text-text-secondary text-light-text-secondary
                    font-semibold
                    border-b dark:border-border border-light-border
                  `,children:l},c))]})}),e.jsx("tbody",{children:S.map((l,c)=>e.jsxs("tr",{className:`
                  transition-colors
                  ${c%2===0?"dark:bg-surface-1 bg-white":"dark:bg-surface-1/50 bg-light-surface-1/50"}
                  dark:hover:bg-surface-2/60 hover:bg-light-surface-2/60
                  border-b dark:border-border/30 border-light-border/30
                `,children:[e.jsx("td",{className:`
                  px-2 py-1.5 text-right whitespace-nowrap
                  dark:text-text-tertiary/30 text-light-text-tertiary/30
                  select-none
                `,children:c+1}),l.map((k,m)=>e.jsx("td",{className:`
                      px-1 py-1
                      dark:text-text-secondary text-light-text-secondary
                      whitespace-nowrap
                    `,children:e.jsx(F,{value:k,isEditing:j?.r===c&&j?.c===m,onStartEdit:()=>g({r:c,c:m}),onCommit:y=>R(c,m,y)})},m))]},c))})]})}),E&&e.jsx("div",{className:"border-t dark:border-border/30 border-light-border/30",children:e.jsx("button",{onClick:()=>h(!a),className:`
              w-full text-center py-2 text-[10px] font-medium cursor-pointer
              dark:text-text-tertiary dark:hover:text-accent
              text-light-text-tertiary hover:text-accent
              transition-colors
            `,children:a?"收起":`顯示全部 ${s.length} 行`})}),e.jsxs("div",{className:`
        flex items-center justify-between
        px-4 py-2 border-t dark:border-border border-light-border
        text-[10px] dark:text-text-tertiary/50 text-light-text-tertiary/50
      `,children:[e.jsx("span",{children:"已加入 BOM 標記，Excel 可正確顯示中文"}),x>0&&e.jsxs("span",{className:"text-accent",children:["已修改 ",x," 個儲存格"]})]})]})}export{W as default};
