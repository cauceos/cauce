import { CopyId } from '../../components/CopyId'
import { shortId } from '../../lib/format'
import type { Tier } from '../../api/types'
import { canHaveChildren } from './useTenantTree'
import type { TreeNode } from './useTenantTree'

interface TreeCallbacks {
  selectedId: string | null
  /** A row still washing teal after an optimistic insert; null otherwise. */
  freshId: string | null
  onSelect(id: string): void
  onToggle(id: string): void
  onLoadMore(id: string): void
  onOpenAgents(clientId: string): void
}

/** OPERATOR teal · PARTNER slate · CLIENT moss — fixed, and the guides reuse it. */
export function tierChipClass(tier: Tier): string {
  return tier === 'OPERATOR' ? 'chip teal' : tier === 'PARTNER' ? 'chip slate' : 'chip moss'
}

/**
 * The tenant tree: a single root node, walked children-first on demand.
 * A real tree for assistive tech (`tree` / `treeitem` / `group`, with
 * `aria-expanded` and `aria-selected`); Enter and Space select, the caret
 * expands. Arrow-key navigation across items is deliberately not claimed.
 */
export function TenantTree({ root, ...cb }: { root: TreeNode } & TreeCallbacks) {
  return (
    <div className="tree" role="tree" aria-label="Tenant hierarchy">
      <TreeNodeRow node={root} depth={0} {...cb} />
    </div>
  )
}

function TreeNodeRow({
  node,
  depth,
  selectedId,
  freshId,
  onSelect,
  onToggle,
  onLoadMore,
  onOpenAgents,
}: { node: TreeNode; depth: number } & TreeCallbacks) {
  const { tenant } = node
  const expandable = canHaveChildren(tenant)
  const isClient = tenant.tier === 'CLIENT'
  const selected = selectedId === tenant.id
  const rowClass = [
    'trow',
    node.expanded && expandable ? 'open' : '',
    selected ? 'sel' : '',
    freshId === tenant.id ? 'fresh' : '',
  ]
    .filter(Boolean)
    .join(' ')

  const row = (
    <div
      className={rowClass}
      role="treeitem"
      aria-level={depth + 1}
      aria-selected={selected}
      aria-expanded={expandable ? node.expanded : undefined}
      tabIndex={0}
      title={tenant.id}
      onClick={() => onSelect(tenant.id)}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          onSelect(tenant.id)
        }
      }}
    >
      {/* CLIENT rows are leaves: the hidden caret keeps the gutter, not the
          arrow — a caret that expands nothing is a lie. */}
      {expandable ? (
        <button
          type="button"
          className="caret"
          aria-label={node.expanded ? 'Collapse' : 'Expand'}
          onClick={(event) => {
            event.stopPropagation()
            onToggle(tenant.id)
          }}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" aria-hidden="true">
            <path d="M9 6l6 6-6 6" />
          </svg>
        </button>
      ) : (
        <span className="caret ph" aria-hidden="true" />
      )}

      <div className="tcol">
        <div className="trow-top">
          <span className="tname">{tenant.name}</span>
          <span className={tierChipClass(tenant.tier)}>{tenant.tier}</span>
        </div>
        <div className="trow-sub">
          {isClient && (
            <button
              type="button"
              className="agents-link"
              onClick={(event) => {
                event.stopPropagation()
                onOpenAgents(tenant.id)
              }}
            >
              agents →
            </button>
          )}
          <span className="tid">{shortId(tenant.id)}</span>
        </div>
      </div>

      <CopyId value={tenant.id} />
      <svg className="chevR" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" aria-hidden="true">
        <path d="M9 6l6 6-6 6" />
      </svg>
    </div>
  )

  const children = expandable && node.expanded && (
    <div role="group">
      {node.childrenStatus === 'loading' && node.children === null && (
        <div className={`lvl lvl-${depth + 1} t-${childTier(tenant.tier)}`}>
          <div className="tree-skel" aria-label="Loading children">
            <div className="skel" style={{ width: '78%' }} />
            <div className="skel" style={{ width: '64%' }} />
            <div className="skel" style={{ width: '70%' }} />
          </div>
        </div>
      )}
      {node.children?.map((child) => (
        <div
          key={child.tenant.id}
          className={`lvl lvl-${depth + 1} t-${child.tenant.tier.toLowerCase()}`}
        >
          <TreeNodeRow
            node={child}
            depth={depth + 1}
            selectedId={selectedId}
            freshId={freshId}
            onSelect={onSelect}
            onToggle={onToggle}
            onLoadMore={onLoadMore}
            onOpenAgents={onOpenAgents}
          />
        </div>
      ))}
      {node.childrenStatus === 'loaded' && node.children?.length === 0 && (
        <div className={`lvl lvl-${depth + 1} t-${childTier(tenant.tier)}`}>
          <div className="leafnote">no children yet</div>
        </div>
      )}
      {node.childrenStatus === 'error' && (
        <div className={`lvl lvl-${depth + 1} t-${childTier(tenant.tier)}`}>
          <div className="leafnote">{node.childrenError}</div>
        </div>
      )}
      {/* Only while this node still has a next_cursor. */}
      {node.nextCursor !== null && (
        <div className={`lvl lvl-${depth + 1} t-${childTier(tenant.tier)}`}>
          <button
            type="button"
            className="morerow"
            disabled={node.childrenStatus === 'loading'}
            onClick={() => onLoadMore(tenant.id)}
          >
            {node.childrenStatus === 'loading' ? 'loading…' : 'load more children…'}
          </button>
        </div>
      )}
    </div>
  )

  return (
    <>
      {row}
      {children}
    </>
  )
}

/** The tier that nests under this one — the colour its guide takes. */
function childTier(tier: Tier): string {
  return tier === 'OPERATOR' ? 'partner' : 'client'
}
