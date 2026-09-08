import { lazy, Suspense } from 'react';
import type { SkinViewerProps } from './SkinViewer';

const SkinViewer = lazy(() => import('./SkinViewer'));

/**
 * Code-split boundary for the 3D player: three.js + skinview3d land in their
 * own chunk that loads only when a view actually mounts the model. The
 * fallback reserves the canvas box (sizing lives on the className) so the
 * surrounding layout doesn't shift while the chunk streams in.
 */
export default function LazySkinViewer(props: SkinViewerProps) {
  return (
    <Suspense fallback={<div className={props.className} />}>
      <SkinViewer {...props} />
    </Suspense>
  );
}
