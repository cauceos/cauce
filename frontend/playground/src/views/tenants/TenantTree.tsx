import { CopyId } from '../../components/CopyId'
import { shortId } from '../../lib/format'
import { canHaveChildren } from './useTenantTree'
import type { TreeNode } from './useTenantTree'

interface TreeCallbacks {
  selectedId: string | null
  onSelect(id: string): void
  onToggle(id: string): void
  onLoadMore(id: string): void
  onOpenAgents(clientId: string): void
}

/** The tenant tree: a single root node, walked children-first on demand. */
export function TenantTree({ root, ...cb }: { root: TreeNode } & TreeCallbacks) {
  return (
    <div className="tree">
      <TreeNodeRow node={root} {...cb} />
    </div>
  )
}

function TreeNodeRow({
  node,
  selectedId,
  onSelect,
  onToggle,
  onLoadMore,
  onOpenAgents,
}: { node: TreeNode } & TreeCallbacks) {
  const { tenant } = node
  const expandable = canHaveChildren(tenant)
  const isClient = tenant.tier === 'CLIENT'
  const caret = !expandable ? '' : node.expanded ? '▼' : '›'

  return (
    <div className="node">
      <div
        className={selectedId === tenant.id ? 'node-row selected' : 'node-row'}
        onClick={() => onSelect(tenant.id)}
        role="button"
        tabIndex={0}
        onKeyDown={(event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault()
            onSelect(tenant.id)
          }
        }}
      >
        <button
          type="button"
          className={expandable ? 'caret' : 'caret leaf'}
          aria-label={expandable ? (node.expanded ? 'Collapse' : 'Expand') : undefined}
          disabled={!expandable}
          onClick={(event) => {
            event.stopPropagation()
            onToggle(tenant.id)
          }}
        >
          {caret}
        </button>
        <span className="node-name">{tenant.name}</span>
        <span className={`tier ${tenant.tier.toLowerCase()}`}>{tenant.tier}</span>
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
        <span className="node-id" onClick={(event) => event.stopPropagation()}>
          {shortId(tenant.id)} <CopyId value={tenant.id} />
        </span>
      </div>

      {expandable && node.expanded && (
        <div className="children">
          {node.childrenStatus === 'loading' && node.children === null && (
            <div className="children-note">loading…</div>
          )}
          {node.children?.map((child) => (
            <TreeNodeRow
              key={child.tenant.id}
              node={child}
              selectedId={selectedId}
              onSelect={onSelect}
              onToggle={onToggle}
              onLoadMore={onLoadMore}
              onOpenAgents={onOpenAgents}
            />
          ))}
          {node.childrenStatus === 'loaded' && node.children?.length === 0 && (
            <div className="children-note">no children yet</div>
          )}
          {node.childrenStatus === 'error' && (
            <div className="children-note">{node.childrenError}</div>
          )}
          {node.nextCursor !== null && (
            <button
              type="button"
              className="load-more-children"
              disabled={node.childrenStatus === 'loading'}
              onClick={() => onLoadMore(tenant.id)}
            >
              {node.childrenStatus === 'loading' ? 'loading…' : 'load more children…'}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
