import {
  createContext,
  useContext,
  useReducer,
  useRef,
  useCallback,
  type ReactNode,
} from 'react';

// ════════════════════════════════════════════
// Tab definitions
// ════════════════════════════════════════════
export type TabId = 'overview' | 'rules' | 'analysis' | 'validation' | 'export' | 'execution';

export const TAB_LIST: { id: TabId; label: string }[] = [
  { id: 'overview', label: '總覽' },
  { id: 'rules', label: '規則表' },
  { id: 'analysis', label: '分析' },
  { id: 'validation', label: '驗證' },
  { id: 'export', label: '交付' },
  { id: 'execution', label: 'Engine Execution' },
];

// ════════════════════════════════════════════
// State
// ════════════════════════════════════════════
export interface DashboardState {
  activeTab: TabId;
  prevTab: TabId | null;
  highlightedRuleIds: string[];
  highlightSource: 'validation' | 'simplification' | 'conflict' | 'heatmap' | null;
  highlightedHeatmapCell: { x: string; y: string } | null;
  scrollTarget: string | null;
  dragState: {
    isDragging: boolean;
    draggedRuleId: string | null;
    overRuleId: string | null;
  };
  sortState: {
    field: string | null;
    direction: 'asc' | 'desc';
  };
  expandedRuleId: string | null;
  ruleOrder: string[] | null;
  jsonSearch: {
    query: string;
    activeMatchIndex: number;
    totalMatches: number;
  };
  shareMode: boolean;
}

const initialState: DashboardState = {
  activeTab: 'overview',
  prevTab: null,
  highlightedRuleIds: [],
  highlightSource: null,
  highlightedHeatmapCell: null,
  scrollTarget: null,
  dragState: { isDragging: false, draggedRuleId: null, overRuleId: null },
  sortState: { field: null, direction: 'asc' },
  expandedRuleId: null,
  ruleOrder: null,
  jsonSearch: { query: '', activeMatchIndex: 0, totalMatches: 0 },
  shareMode: false,
};

// ════════════════════════════════════════════
// Actions
// ════════════════════════════════════════════
export type DashboardAction =
  | { type: 'SET_TAB'; tab: TabId }
  | { type: 'SET_HIGHLIGHTED_RULE_IDS'; ruleIds: string[]; source: DashboardState['highlightSource'] }
  | { type: 'CLEAR_HIGHLIGHT' }
  | { type: 'SET_HIGHLIGHTED_HEATMAP_CELL'; cell: { x: string; y: string } | null }
  | { type: 'SET_SCROLL_TARGET'; target: string | null }
  | { type: 'SET_DRAG_STATE'; dragState: DashboardState['dragState'] }
  | { type: 'SET_SORT'; field: string | null; direction: 'asc' | 'desc' }
  | { type: 'TOGGLE_EXPAND_RULE'; ruleId: string }
  | { type: 'SET_RULE_ORDER'; order: string[] | null }
  | { type: 'SET_JSON_SEARCH'; search: DashboardState['jsonSearch'] }
  | { type: 'SET_SHARE_MODE'; enabled: boolean }
  | {
      type: 'NAVIGATE_AND_HIGHLIGHT';
      tab: TabId;
      ruleIds: string[];
      source: DashboardState['highlightSource'];
      scrollTarget: string | null;
    }
  | {
      type: 'NAVIGATE_AND_HIGHLIGHT_CELL';
      tab: TabId;
      cell: { x: string; y: string };
    };

// ════════════════════════════════════════════
// Reducer
// ════════════════════════════════════════════
export function dashboardReducer(state: DashboardState, action: DashboardAction): DashboardState {
  switch (action.type) {
    case 'SET_TAB':
      return {
        ...state,
        prevTab: state.activeTab,
        activeTab: action.tab,
      };

    case 'SET_HIGHLIGHTED_RULE_IDS':
      return {
        ...state,
        highlightedRuleIds: action.ruleIds,
        highlightSource: action.source,
      };

    case 'CLEAR_HIGHLIGHT':
      return {
        ...state,
        highlightedRuleIds: [],
        highlightSource: null,
        highlightedHeatmapCell: null,
        scrollTarget: null,
      };

    case 'SET_HIGHLIGHTED_HEATMAP_CELL':
      return {
        ...state,
        highlightedHeatmapCell: action.cell,
      };

    case 'SET_SCROLL_TARGET':
      return {
        ...state,
        scrollTarget: action.target,
      };

    case 'SET_DRAG_STATE':
      return {
        ...state,
        dragState: action.dragState,
      };

    case 'SET_SORT':
      return {
        ...state,
        sortState: { field: action.field, direction: action.direction },
      };

    case 'TOGGLE_EXPAND_RULE':
      return {
        ...state,
        expandedRuleId: state.expandedRuleId === action.ruleId ? null : action.ruleId,
      };

    case 'SET_RULE_ORDER':
      return {
        ...state,
        ruleOrder: action.order,
      };

    case 'SET_JSON_SEARCH':
      return {
        ...state,
        jsonSearch: action.search,
      };

    case 'SET_SHARE_MODE':
      return {
        ...state,
        shareMode: action.enabled,
      };

    case 'NAVIGATE_AND_HIGHLIGHT':
      return {
        ...state,
        prevTab: state.activeTab,
        activeTab: action.tab,
        highlightedRuleIds: action.ruleIds,
        highlightSource: action.source,
        scrollTarget: action.scrollTarget,
        highlightedHeatmapCell: null,
      };

    case 'NAVIGATE_AND_HIGHLIGHT_CELL':
      return {
        ...state,
        prevTab: state.activeTab,
        activeTab: action.tab,
        highlightedHeatmapCell: action.cell,
        highlightedRuleIds: [],
        highlightSource: 'heatmap',
        scrollTarget: null,
      };

    default:
      return state;
  }
}

// ════════════════════════════════════════════
// Context
// ════════════════════════════════════════════
interface DashboardContextValue {
  state: DashboardState;
  dispatch: React.Dispatch<DashboardAction>;
  registerRef: (id: string, el: HTMLElement | null) => void;
  scrollToRef: (id: string) => void;
}

const DashboardContext = createContext<DashboardContextValue | null>(null);

// ════════════════════════════════════════════
// Provider
// ════════════════════════════════════════════
export function DashboardProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(dashboardReducer, initialState);
  const refMap = useRef<Map<string, HTMLElement>>(new Map());

  const registerRef = useCallback((id: string, el: HTMLElement | null) => {
    if (el) {
      refMap.current.set(id, el);
    } else {
      refMap.current.delete(id);
    }
  }, []);

  const scrollToRef = useCallback((id: string) => {
    const el = refMap.current.get(id);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }, []);

  return (
    <DashboardContext.Provider value={{ state, dispatch, registerRef, scrollToRef }}>
      {children}
    </DashboardContext.Provider>
  );
}

// ════════════════════════════════════════════
// Hook
// ════════════════════════════════════════════
export function useDashboard(): DashboardContextValue {
  const ctx = useContext(DashboardContext);
  if (!ctx) {
    throw new Error('useDashboard must be used within a DashboardProvider');
  }
  return ctx;
}

// ════════════════════════════════════════════
// Action creators
// ════════════════════════════════════════════
export const actions = {
  setTab: (tab: TabId): DashboardAction => ({
    type: 'SET_TAB',
    tab,
  }),

  highlightRules: (
    ruleIds: string[],
    source: DashboardState['highlightSource'],
  ): DashboardAction => ({
    type: 'SET_HIGHLIGHTED_RULE_IDS',
    ruleIds,
    source,
  }),

  clearHighlight: (): DashboardAction => ({
    type: 'CLEAR_HIGHLIGHT',
  }),

  highlightHeatmapCell: (cell: { x: string; y: string } | null): DashboardAction => ({
    type: 'SET_HIGHLIGHTED_HEATMAP_CELL',
    cell,
  }),

  setScrollTarget: (target: string | null): DashboardAction => ({
    type: 'SET_SCROLL_TARGET',
    target,
  }),

  setDragState: (dragState: DashboardState['dragState']): DashboardAction => ({
    type: 'SET_DRAG_STATE',
    dragState,
  }),

  setSort: (field: string | null, direction: 'asc' | 'desc'): DashboardAction => ({
    type: 'SET_SORT',
    field,
    direction,
  }),

  toggleExpandRule: (ruleId: string): DashboardAction => ({
    type: 'TOGGLE_EXPAND_RULE',
    ruleId,
  }),

  setRuleOrder: (order: string[] | null): DashboardAction => ({
    type: 'SET_RULE_ORDER',
    order,
  }),

  setJsonSearch: (search: DashboardState['jsonSearch']): DashboardAction => ({
    type: 'SET_JSON_SEARCH',
    search,
  }),

  setShareMode: (enabled: boolean): DashboardAction => ({
    type: 'SET_SHARE_MODE',
    enabled,
  }),

  navigateAndHighlight: (
    tab: TabId,
    ruleIds: string[],
    source: DashboardState['highlightSource'],
    scrollTarget: string | null = null,
  ): DashboardAction => ({
    type: 'NAVIGATE_AND_HIGHLIGHT',
    tab,
    ruleIds,
    source,
    scrollTarget,
  }),

  navigateAndHighlightCell: (
    tab: TabId,
    cell: { x: string; y: string },
  ): DashboardAction => ({
    type: 'NAVIGATE_AND_HIGHLIGHT_CELL',
    tab,
    cell,
  }),
};
