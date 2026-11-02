import { Component, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  CheckoutApiService,
  CheckoutRecoveryHistoryEntry,
  MerchantDashboardResponse,
} from './checkout-api.service';

@Component({
  selector: 'app-merchant-dashboard',
  imports: [RouterLink],
  templateUrl: './merchant-dashboard.html',
  styleUrl: './merchant-dashboard.scss',
})
export class MerchantDashboardComponent implements OnInit {
  protected readonly dashboard = signal<MerchantDashboardResponse | null>(null);
  protected readonly recoveryHistory = signal<CheckoutRecoveryHistoryEntry[]>([]);
  protected readonly isLoading = signal(true);
  protected readonly errorMessage = signal('');

  constructor(private readonly checkoutApi: CheckoutApiService) {}

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  protected async refresh(): Promise<void> {
    this.isLoading.set(true);
    this.errorMessage.set('');
    try {
      const [dashboard, recoveryHistory] = await Promise.all([
        this.checkoutApi.getMerchantDashboard(),
        this.checkoutApi.getMerchantRecoveryHistory(),
      ]);
      this.dashboard.set(dashboard);
      this.recoveryHistory.set(recoveryHistory);
    } catch {
      this.errorMessage.set('The merchant dashboard is unavailable. Please try again.');
    } finally {
      this.isLoading.set(false);
    }
  }

  protected statusCount(status: 'COMPLETED' | 'PENDING' | 'FAILED' | 'CANCELLED'): number {
    return this.dashboard()?.statusCounts[status] ?? 0;
  }

  protected formatDate(timestamp?: string): string {
    if (!timestamp) return '—';
    const date = new Date(timestamp);
    return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString();
  }

  protected formatAmount(amount: number, currency: string): string {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount);
  }
}
