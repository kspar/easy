import {
  type ColumnDef,
  type RowSelectionState,
  type SortingState,
  type OnChangeFn,
  useReactTable,
  getCoreRowModel,
  getSortedRowModel,
  flexRender,
} from '@tanstack/react-table'
import {
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TableSortLabel,
  Checkbox,
} from '@mui/material'

interface DataTableProps<T> {
  columns: ColumnDef<T, any>[]
  data: T[]
  getRowId: (row: T) => string
  rowSelection?: RowSelectionState
  onRowSelectionChange?: OnChangeFn<RowSelectionState>
  sorting?: SortingState
  onSortingChange?: OnChangeFn<SortingState>
}

export default function DataTable<T>({
  columns,
  data,
  getRowId,
  rowSelection,
  onRowSelectionChange,
  sorting,
  onSortingChange,
}: DataTableProps<T>) {
  const hasSelection = rowSelection !== undefined && onRowSelectionChange !== undefined
  const hasSorting = sorting !== undefined && onSortingChange !== undefined

  const table = useReactTable({
    data,
    columns,
    getRowId,
    getCoreRowModel: getCoreRowModel(),
    state: {
      ...(hasSorting && { sorting }),
      ...(hasSelection && { rowSelection }),
    },
    ...(hasSorting && {
      getSortedRowModel: getSortedRowModel(),
      onSortingChange,
    }),
    ...(hasSelection && {
      onRowSelectionChange,
      enableRowSelection: true,
    }),
  })

  return (
    <TableContainer>
      <Table size="small">
        <TableHead>
          {table.getHeaderGroups().map((headerGroup) => (
            <TableRow key={headerGroup.id}>
              {hasSelection && (
                <TableCell padding="checkbox">
                  <Checkbox
                    indeterminate={table.getIsSomeRowsSelected()}
                    checked={table.getIsAllRowsSelected()}
                    onChange={table.getToggleAllRowsSelectedHandler()}
                  />
                </TableCell>
              )}
              {headerGroup.headers.map((header) => {
                const canSort = hasSorting && header.column.getCanSort()
                const sorted = header.column.getIsSorted()
                return (
                  <TableCell key={header.id}>
                    {canSort ? (
                      <TableSortLabel
                        active={!!sorted}
                        direction={sorted || undefined}
                        onClick={header.column.getToggleSortingHandler()}
                      >
                        {flexRender(header.column.columnDef.header, header.getContext())}
                      </TableSortLabel>
                    ) : (
                      flexRender(header.column.columnDef.header, header.getContext())
                    )}
                  </TableCell>
                )
              })}
            </TableRow>
          ))}
        </TableHead>
        <TableBody>
          {table.getRowModel().rows.map((row) => (
            <TableRow key={row.id} selected={hasSelection && row.getIsSelected()}>
              {hasSelection && (
                <TableCell padding="checkbox">
                  <Checkbox
                    checked={row.getIsSelected()}
                    onChange={row.getToggleSelectedHandler()}
                  />
                </TableCell>
              )}
              {row.getVisibleCells().map((cell) => (
                <TableCell key={cell.id}>
                  {flexRender(cell.column.columnDef.cell, cell.getContext())}
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}
