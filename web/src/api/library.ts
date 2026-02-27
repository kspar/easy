import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from './client.ts'
import type { LibraryDirResp, LibraryDirParent, LibraryExerciseDetail, DirAccessesResp, DirAccessLevel } from './types.ts'

export function useLibraryDir(dirId: string) {
  return useQuery({
    queryKey: ['library', 'dir', dirId],
    queryFn: () => apiFetch<LibraryDirResp>(`/lib/dirs/${dirId}`),
  })
}

export function useLibraryDirParents(dirId: string | undefined) {
  return useQuery({
    queryKey: ['library', 'dir', dirId, 'parents'],
    queryFn: () =>
      apiFetch<{ parents: LibraryDirParent[] }>(`/lib/dirs/${dirId}/parents`).then((r) => r.parents.reverse()),
    enabled: !!dirId && dirId !== 'root',
  })
}

export function useLibraryExercise(exerciseId: string | undefined) {
  return useQuery({
    queryKey: ['library', 'exercise', exerciseId],
    queryFn: () => apiFetch<LibraryExerciseDetail>(`/exercises/${exerciseId}`),
    enabled: !!exerciseId,
  })
}

export function useCreateDir() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { name: string; parent_dir_id: string | null }) =>
      apiFetch<{ id: string }>('/lib/dirs', { method: 'POST', body }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['library'] })
    },
  })
}

export function useUpdateDir() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ dirId, name }: { dirId: string; name: string }) =>
      apiFetch(`/lib/dirs/${dirId}`, { method: 'PATCH', body: { name } }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['library'] })
    },
  })
}

export function useDeleteDir() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (dirId: string) =>
      apiFetch(`/lib/dirs/${dirId}`, { method: 'DELETE' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['library'] })
    },
  })
}

export function useCreateExercise() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: {
      title: string
      parent_dir_id: string | null
      public: boolean
      grader_type: string
      solution_file_name: string
      solution_file_type: string
      anonymous_autoassess_enabled: boolean
    }) => apiFetch<{ id: string }>('/exercises', { method: 'POST', body }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['library'] })
    },
  })
}

export function useDeleteExercise() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (exerciseId: string) =>
      apiFetch(`/exercises/${exerciseId}`, { method: 'DELETE' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['library'] })
    },
  })
}

export function useDirAccesses(dirId: string | undefined) {
  return useQuery({
    queryKey: ['library', 'dir', dirId, 'access'],
    queryFn: () => apiFetch<DirAccessesResp>(`/lib/dirs/${dirId}/access`),
    enabled: !!dirId,
  })
}

export function usePutDirAccess(dirId: string | undefined) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: {
      group_id?: string
      email?: string
      any_access?: boolean
      access_level: DirAccessLevel | null
    }) => apiFetch(`/lib/dirs/${dirId}/access`, { method: 'PUT', body }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['library', 'dir', dirId, 'access'] })
      queryClient.invalidateQueries({ queryKey: ['library', 'dir'] })
    },
  })
}

export function useAddExerciseToCourse() {
  return useMutation({
    mutationFn: ({
      courseId,
      exerciseId,
    }: {
      courseId: string
      exerciseId: string
    }) =>
      apiFetch<{ id: string }>(`/teacher/courses/${courseId}/exercises`, {
        method: 'POST',
        body: {
          exercise_id: exerciseId,
          threshold: 100,
          student_visible: false,
          assessments_student_visible: true,
        },
      }),
  })
}
