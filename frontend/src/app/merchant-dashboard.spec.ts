import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  CheckoutApiService,
  CheckoutRecoveryHistoryEntry,
  MerchantDashboardResponse,
} from './checkout-api.service';
import { MerchantDashboardComponent } from './merchant-dashboard';

describe('MerchantDashboardComponent', () => {
  let api: jasmine.SpyObj<CheckoutApiService>;

  const dashboard: MerchantDashboardResponse = {
    totalCheckoutSessions: 2,
    statusCounts: {
      CREATED: 0,
      APPROVED: 0,
      COMPLETED: 1,
      PENDING: 0,
      FAILED: 1,
      CANCELLED: 0,
    },
    checkouts: [
      {
        id: 'checkout-completed',
        productId: 'everyday-backpack',
        productName: 'Everyday Backpack',
        quantity: 1,
        unitPrice: 68,
        total: 68,
        currency: 'USD',
        status: 'COMPLETED',
        createdAt: '2026-11-02T00:00:00Z',
        paypalOrderId: 'sandbox-order-id',
        paypalCaptureId: 'verified-capture-id',
      },
      {
        id: 'checkout-failed',
        productId: 'wireless-earbuds',
        productName: 'Wireless Earbuds',
        quantity: 2,
        unitPrice: 89.99,
        total: 179.98,
        currency: 'USD',
        status: 'FAILED',
        createdAt: '2026-11-01T00:00:00Z',
      },
    ],
  };
  const history: CheckoutRecoveryHistoryEntry[] = [
    {
      checkoutSessionId: 'checkout-failed',
      problemDescription: 'Checkout page stopped responding.',
      detectedIssue: 'Checkout could not be completed',
      explanation: 'The provider could not produce advice.',
      recommendedNextAction: 'Start a new checkout after confirming no separate payment was made.',
      safetyWarning: 'Do not retry if a charge is visible.',
      adviceSource: 'FALLBACK',
      timestamp: '2026-11-02T00:05:00Z',
      paymentStatus: 'FAILED',
    },
  ];

  beforeEach(async () => {
    api = jasmine.createSpyObj<CheckoutApiService>('CheckoutApiService', [
      'getMerchantDashboard',
      'getMerchantRecoveryHistory',
    ]);
    api.getMerchantDashboard.and.resolveTo(dashboard);
    api.getMerchantRecoveryHistory.and.resolveTo(history);

    await TestBed.configureTestingModule({
      imports: [MerchantDashboardComponent],
      providers: [
        provideRouter([]),
        { provide: CheckoutApiService, useValue: api },
      ],
    }).compileComponents();
  });

  async function createFixture(): Promise<ComponentFixture<MerchantDashboardComponent>> {
    const fixture = TestBed.createComponent(MerchantDashboardComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('loads status summaries, checkout details, and recovery history', async () => {
    const fixture = await createFixture();
    const element = fixture.nativeElement as HTMLElement;

    expect(api.getMerchantDashboard).toHaveBeenCalledTimes(1);
    expect(api.getMerchantRecoveryHistory).toHaveBeenCalledTimes(1);
    expect(element.querySelector('.total-card strong')?.textContent).toContain('2');
    expect(element.querySelector('.completed-card strong')?.textContent).toContain('1');
    expect(element.querySelector('.failed-card strong')?.textContent).toContain('1');
    expect(element.querySelector('tbody')?.textContent).toContain('Everyday Backpack');
    expect(element.querySelector('tbody')?.textContent).toContain('COMPLETED');
    expect(element.querySelector('tbody')?.textContent).toContain('verified-capture-id');
    expect(element.querySelector('.history-card')?.textContent)
      .toContain('Checkout page stopped responding.');
    expect(element.querySelector('.source-badge')?.textContent).toContain('Fallback guidance');
    expect(element.querySelector('.history-card')?.textContent)
      .toContain(history[0].recommendedNextAction);
  });

  it('shows an explicit loading error and supports retry', async () => {
    api.getMerchantDashboard.and.rejectWith(new Error('Unavailable'));
    const fixture = await createFixture();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('[role="alert"]')?.textContent)
      .toContain('The merchant dashboard is unavailable');

    api.getMerchantDashboard.and.resolveTo(dashboard);
    element.querySelector<HTMLButtonElement>('.refresh-button')!.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(element.querySelector('[role="alert"]')).toBeNull();
    expect(element.querySelector('.summary-grid')).toBeTruthy();
  });
});
