import { Icon } from "./common";
import type { TFunction } from "../types";

interface CursorPagerProps {
  page: number;
  total: number;
  loading: boolean;
  canPrevious: boolean;
  canNext: boolean;
  onPrevious: () => void;
  onNext: () => void;
  t: TFunction;
}

export function CursorPager({ page, total, loading, canPrevious, canNext, onPrevious, onNext, t }: CursorPagerProps) {
  return (
    <div className="cursor-pager" aria-label={t("Pagination")}>
      <span>{t("Page")} {page}</span>
      <span>{total} {t("total")}</span>
      <div className="btn-group btn-group-sm">
        <button className="btn btn-outline-secondary" disabled={loading || !canPrevious} onClick={onPrevious} aria-label={t("Previous page")}>
          <Icon name="chevron-left" />
        </button>
        <button className="btn btn-outline-secondary" disabled={loading || !canNext} onClick={onNext} aria-label={t("Next page")}>
          <Icon name="chevron-right" />
        </button>
      </div>
    </div>
  );
}
