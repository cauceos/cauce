import { useCallback, useEffect, useRef, useState } from 'react'
import type { ApiClient } from '../../api/client'
import { ApiError, NetworkError } from '../../api/errors'
import type { TenantResponse } from '../../api/types'

const CHILDREN_PAGE_LIMIT = 50

type LoadStatus = 'idle' | 'loading' | 'loaded' | 'error'

/**
 * One node of the tenant tree. `children === null` means "not fetched yet";
 * an empty array means fetched-and-empty. `nextCursor` is the keyset cursor
 * for the *next* page of this node's children (null once exhausted).
 */
export interface TreeNode {
  tenant: TenantResponse
  children: TreeNode[] | null
  nextCursor: string | null
  expanded: boolean
  childrenStatus: LoadStatus
  childrenError: string | null
}

export interface TenantTreeState {
  rootStatus: LoadStatus
  rootError: string | null
  root: TreeNode | null
  selectedId: string | null
}

function nodeOf(tenant: TenantResponse): TreeNode {
  return {
    tenant,
    children: null,
    nextCursor: null,
    expanded: false,
    childrenStatus: 'idle',
    childrenError: null,
  }
}

/** True for tiers that can hold children (agents live under CLIENT, not tenants). */
export function canHaveChildren(tenant: TenantResponse): boolean {
  return tenant.tier !== 'CLIENT'
}

/**
 * Immutably rewrite the node with `id` somewhere in the tree, returning a
 * new root. The tree is only three levels deep, so plain recursion is fine.
 */
function mapNode(root: TreeNode, id: string, fn: (node: TreeNode) => TreeNode): TreeNode {
  if (root.tenant.id === id) return fn(root)
  if (root.children === null) return root
  let changed = false
  const children = root.children.map((child) => {
    const next = mapNode(child, id, fn)
    if (next !== child) changed = true
    return next
  })
  return changed ? { ...root, children } : root
}

function findNode(root: TreeNode | null, id: string): TreeNode | null {
  if (root === null) return null
  if (root.tenant.id === id) return root
  if (root.children === null) return null
  for (const child of root.children) {
    const found = findNode(child, id)
    if (found !== null) return found
  }
  return null
}

/**
 * The tenant tree: load a root by id, then walk children on demand. There
 * is no global tenant listing — discovery is children-walking from a known
 * root (the honest shape of the API). After a create, the returned tenant
 * is inserted optimistically into its parent's loaded children, so it shows
 * even though its UUIDv7 would otherwise land on a not-yet-fetched page.
 */
export function useTenantTree(client: ApiClient) {
  const [state, setState] = useState<TenantTreeState>({
    rootStatus: 'idle',
    rootError: null,
    root: null,
    selectedId: null,
  })
  const genRef = useRef(0)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(
    () => () => {
      genRef.current += 1
      abortRef.current?.abort()
    },
    [],
  )

  /** Fetch one page of a node's children and fold it into the tree. */
  const fetchChildren = useCallback(
    async (nodeId: string, cursor: string | undefined) => {
      setState((prev) => ({
        ...prev,
        root:
          prev.root === null
            ? prev.root
            : mapNode(prev.root, nodeId, (node) => ({
                ...node,
                expanded: true,
                childrenStatus: 'loading',
                childrenError: null,
              })),
      }))
      try {
        const page = await client.listChildren(
          nodeId,
          cursor !== undefined ? { limit: CHILDREN_PAGE_LIMIT, cursor } : { limit: CHILDREN_PAGE_LIMIT },
        )
        const fetched = page.data.map(nodeOf)
        setState((prev) => ({
          ...prev,
          root:
            prev.root === null
              ? prev.root
              : mapNode(prev.root, nodeId, (node) => ({
                  ...node,
                  children: [...(node.children ?? []), ...fetched],
                  nextCursor: page.next_cursor,
                  childrenStatus: 'loaded',
                  childrenError: null,
                })),
        }))
      } catch (cause) {
        setState((prev) => ({
          ...prev,
          root:
            prev.root === null
              ? prev.root
              : mapNode(prev.root, nodeId, (node) => ({
                  ...node,
                  childrenStatus: 'error',
                  childrenError: describeError(cause),
                })),
        }))
      }
    },
    [client],
  )

  const loadRoot = useCallback(
    async (rawId: string) => {
      const id = rawId.trim()
      if (id === '') return
      genRef.current += 1
      const gen = genRef.current
      abortRef.current?.abort()
      const controller = new AbortController()
      abortRef.current = controller
      setState({ rootStatus: 'loading', rootError: null, root: null, selectedId: null })
      try {
        const tenant = await client.getTenant(id, controller.signal)
        if (gen !== genRef.current) return
        const root: TreeNode = { ...nodeOf(tenant), expanded: true }
        setState({ rootStatus: 'loaded', rootError: null, root, selectedId: tenant.id })
        if (canHaveChildren(tenant)) void fetchChildren(tenant.id, undefined)
      } catch (cause) {
        if (gen !== genRef.current) return
        setState({ rootStatus: 'error', rootError: describeError(cause), root: null, selectedId: null })
      }
    },
    [client, fetchChildren],
  )

  /** Toggle a node's expansion; first expansion lazily fetches page one. */
  const toggle = useCallback(
    (nodeId: string) => {
      const node = findNode(state.root, nodeId)
      if (node === null || !canHaveChildren(node.tenant)) return
      if (node.childrenStatus === 'idle') {
        void fetchChildren(nodeId, undefined)
        return
      }
      setState((prev) => ({
        ...prev,
        root:
          prev.root === null
            ? prev.root
            : mapNode(prev.root, nodeId, (n) => ({ ...n, expanded: !n.expanded })),
      }))
    },
    [state.root, fetchChildren],
  )

  /** Select a node; ensure its children are loaded so a create lands visibly. */
  const select = useCallback(
    (nodeId: string) => {
      setState((prev) => ({ ...prev, selectedId: nodeId }))
      const node = findNode(state.root, nodeId)
      if (node !== null && canHaveChildren(node.tenant) && node.childrenStatus === 'idle') {
        void fetchChildren(nodeId, undefined)
      }
    },
    [state.root, fetchChildren],
  )

  const loadMoreChildren = useCallback(
    (nodeId: string) => {
      const node = findNode(state.root, nodeId)
      if (node === null || node.nextCursor === null || node.childrenStatus === 'loading') return
      void fetchChildren(nodeId, node.nextCursor)
    },
    [state.root, fetchChildren],
  )

  /** Fold a freshly created child into its parent's loaded children. */
  const insertChild = useCallback((parentId: string, created: TenantResponse) => {
    setState((prev) => ({
      ...prev,
      root:
        prev.root === null
          ? prev.root
          : mapNode(prev.root, parentId, (node) => {
              const existing = node.children ?? []
              if (existing.some((c) => c.tenant.id === created.id)) return { ...node, expanded: true }
              return {
                ...node,
                expanded: true,
                children: [...existing, nodeOf(created)],
                childrenStatus: 'loaded',
              }
            }),
    }))
  }, [])

  return { ...state, loadRoot, toggle, select, loadMoreChildren, insertChild }
}

function describeError(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.status === 404) return 'no tenant visible for this id (it may not exist, or not be yours)'
    if (cause.code === 'invalid_path_parameter' || cause.status === 400) return 'not a valid tenant id'
    return `${cause.status} ${cause.code} — ${cause.message}`
  }
  if (cause instanceof NetworkError) return 'request failed — is the instance still reachable?'
  return cause instanceof Error ? cause.message : String(cause)
}
