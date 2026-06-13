function createFolderNode(id, name) {
  return {
    id,
    name,
    isLeaf: false,
    children: [],
    size: 0,
  }
}

function createFileNode(id, file) {
  return {
    id,
    name: file.name,
    isLeaf: true,
    children: null,
    size: file.size || 0,
  }
}

export function sortTree(nodes) {
  nodes.sort((a, b) => {
    if (a.isLeaf === b.isLeaf) {
      return a.name.localeCompare(b.name)
    }

    return a.isLeaf ? 1 : -1
  })

  nodes.forEach((node) => {
    if (node.children?.length) {
      sortTree(node.children)
    }
  })
}

export function buildFolderTree(files) {
  const tree = []
  const pathMap = new Map()
  const fileMap = new Map()
  const expandedKeys = []
  let nodeId = 0

  files.forEach((file) => {
    const pathSegments = String(file.webkitRelativePath || file.name)
      .split('/')
      .filter(Boolean)

    if (!pathSegments.length) {
      return
    }

    let currentNodes = tree
    let currentPath = ''

    pathSegments.forEach((segment, index) => {
      currentPath += `${currentPath ? '/' : ''}${segment}`
      const isLeaf = index === pathSegments.length - 1

      let node = pathMap.get(currentPath)
      if (!node) {
        node = isLeaf ? createFileNode(++nodeId, file) : createFolderNode(++nodeId, segment)

        currentNodes.push(node)
        pathMap.set(currentPath, node)

        if (isLeaf) {
          fileMap.set(node.id, file)
        } else {
          expandedKeys.push(node.id)
        }
      }

      currentNodes = node.children || []
    })
  })

  sortTree(tree)

  return {
    tree,
    expandedKeys,
    fileMap,
  }
}

export function getFolderTreeStats(nodes) {
  const stats = {
    fileCount: 0,
    folderCount: 0,
    totalSize: 0,
  }

  const walk = (currentNodes) => {
    currentNodes.forEach((node) => {
      if (node.isLeaf) {
        stats.fileCount += 1
        stats.totalSize += node.size || 0
        return
      }

      stats.folderCount += 1
      if (node.children?.length) {
        walk(node.children)
      }
    })
  }

  walk(nodes)
  return stats
}
