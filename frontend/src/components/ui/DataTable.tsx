'use client';

import { ReactNode } from 'react';
import {
  Box,
  Card,
  CardActionArea,
  Divider,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TableSortLabel,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import { EmptyState, ErrorState, TableSkeleton } from './States';

export interface Column<T> {
  key: string;
  header: ReactNode;
  render: (row: T) => ReactNode;
  /** Server sort field name; enables the sort label. */
  sortKey?: string;
  align?: 'left' | 'right' | 'center';
  width?: number | string;
  /** Hide on the mobile card layout. */
  hideOnMobile?: boolean;
  /** Mark as the card title on mobile (first column by default). */
  primary?: boolean;
}

export interface SortState {
  field: string;
  direction: 'asc' | 'desc';
}

export interface DataTableProps<T> {
  columns: Column<T>[];
  rows: T[] | undefined;
  rowKey: (row: T) => string;
  loading?: boolean;
  error?: unknown;
  onRetry?: () => void;
  onRowClick?: (row: T) => void;
  /** Server-side pagination (omit for client-only lists). */
  page?: number;
  pageSize?: number;
  total?: number;
  onPageChange?: (page: number) => void;
  onPageSizeChange?: (size: number) => void;
  pageSizeOptions?: number[];
  sort?: SortState;
  onSortChange?: (sort: SortState) => void;
  emptyTitle?: string;
  emptyDescription?: ReactNode;
  emptyAction?: ReactNode;
  /** Optional custom mobile card renderer. */
  mobileCard?: (row: T) => ReactNode;
  dense?: boolean;
  ariaLabel?: string;
  /** Row-level emphasis (e.g. overdue). */
  rowSx?: (row: T) => object | undefined;
}

/** Filterable, sortable, paginated table that switches to readable cards on small screens. */
export function DataTable<T>(props: DataTableProps<T>) {
  const {
    columns,
    rows,
    rowKey,
    loading,
    error,
    onRetry,
    onRowClick,
    page,
    pageSize,
    total,
    onPageChange,
    onPageSizeChange,
    pageSizeOptions = [10, 20, 50],
    sort,
    onSortChange,
    emptyTitle = 'Nothing to show yet',
    emptyDescription,
    emptyAction,
    mobileCard,
    dense,
    ariaLabel,
    rowSx,
  } = props;
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down('md'));

  if (error) {
    return (
      <Box sx={{ p: 4 }}>
        <ErrorState error={error} onRetry={onRetry} />
      </Box>
    );
  }
  if (loading && !rows) {
    return <TableSkeleton columns={Math.min(columns.length, 6)} />;
  }
  if (!rows || rows.length === 0) {
    return <EmptyState title={emptyTitle} description={emptyDescription} action={emptyAction} compact />;
  }

  const pagination =
    onPageChange && page !== undefined && pageSize !== undefined && total !== undefined ? (
      <TablePagination
        component="div"
        count={total}
        page={page}
        rowsPerPage={pageSize}
        rowsPerPageOptions={pageSizeOptions}
        onPageChange={(_, p) => onPageChange(p)}
        onRowsPerPageChange={(e) => onPageSizeChange?.(parseInt(e.target.value, 10))}
        sx={{ borderTop: 1, borderColor: 'divider' }}
      />
    ) : null;

  if (isMobile) {
    const primary = columns.find((c) => c.primary) ?? columns[0];
    return (
      <Box>
        <Stack spacing={3} sx={{ p: 3, opacity: loading ? 0.6 : 1 }}>
          {rows.map((row) => {
            const content = mobileCard ? (
              mobileCard(row)
            ) : (
              <Box sx={{ p: 4 }}>
                <Box sx={{ fontWeight: 600, mb: 2 }}>{primary.render(row)}</Box>
                <Stack spacing={1.5} divider={<Divider flexItem />}>
                  {columns
                    .filter((c) => c !== primary && !c.hideOnMobile)
                    .map((c) => (
                      <Stack key={c.key} direction="row" justifyContent="space-between" spacing={3} alignItems="center">
                        <Typography variant="caption" color="text.secondary" sx={{ flexShrink: 0 }}>
                          {c.header}
                        </Typography>
                        <Box sx={{ textAlign: 'right', minWidth: 0, fontSize: '0.875rem' }}>{c.render(row)}</Box>
                      </Stack>
                    ))}
                </Stack>
              </Box>
            );
            return (
              <Card key={rowKey(row)} sx={{ ...rowSx?.(row) }}>
                {onRowClick ? <CardActionArea onClick={() => onRowClick(row)}>{content}</CardActionArea> : content}
              </Card>
            );
          })}
        </Stack>
        {pagination}
      </Box>
    );
  }

  return (
    <Box>
      <TableContainer sx={{ opacity: loading ? 0.6 : 1, transition: 'opacity 120ms' }}>
        <Table size={dense ? 'small' : 'medium'} aria-label={ariaLabel} aria-busy={loading ? 'true' : 'false'}>
          <TableHead>
            <TableRow>
              {columns.map((c) => (
                <TableCell key={c.key} align={c.align} sx={{ width: c.width, whiteSpace: 'nowrap' }}>
                  {c.sortKey && onSortChange ? (
                    <TableSortLabel
                      active={sort?.field === c.sortKey}
                      direction={sort && sort.field === c.sortKey ? sort.direction : 'asc'}
                      onClick={() =>
                        onSortChange({
                          field: c.sortKey!,
                          direction: sort && sort.field === c.sortKey && sort.direction === 'asc' ? 'desc' : 'asc',
                        })
                      }
                    >
                      {c.header}
                    </TableSortLabel>
                  ) : (
                    c.header
                  )}
                </TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((row) => (
              <TableRow
                key={rowKey(row)}
                hover={!!onRowClick}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                onKeyDown={onRowClick ? (e) => e.key === 'Enter' && onRowClick(row) : undefined}
                tabIndex={onRowClick ? 0 : undefined}
                sx={{ cursor: onRowClick ? 'pointer' : undefined, ...rowSx?.(row) }}
              >
                {columns.map((c) => (
                  <TableCell key={c.key} align={c.align}>
                    {c.render(row)}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      {pagination}
    </Box>
  );
}

/** Converts a SortState to the backend's "field,dir" sort parameter. */
export function sortParam(sort?: SortState) {
  return sort ? `${sort.field},${sort.direction}` : undefined;
}
