import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { TimeoutError } from 'rxjs';
import { provideRouter } from '@angular/router';
import {
  CheckoutApiService,
  CheckoutRecoveryAdvice,
  CheckoutSession,
  PaymentResult,
  Product,
} from './checkout-api.service';
import { StorefrontComponent } from './storefront';
import { PayPalButtonsOptions, PayPalSdk } from './paypal.types';

describe('StorefrontComponent', () => {
  let api: jasmine.SpyObj<CheckoutApiService>;
  let buttonOptions: PayPalButtonsOptions | undefined;
  let previousPayPal: PayPalSdk | undefined;

  const product: Product = {
    id: 'everyday-backpack',
    name: 'Everyday Backpack',
    description: 'Demo backpack',
    price: 68,
    currency: 'USD',
  };
  const session: CheckoutSession = {
    id: 'session-123',
    productId: product.id,
    productName: product.name,
    quantity: 1,
    unitPrice: 68,
    total: 68,
    currency: 'USD',
    status: 'CREATED',
  };
  const recoveryAdvice: CheckoutRecoveryAdvice = {
    detectedIssue: 'Checkout page stalled',
    explanation: 'The payment step may not have finished loading.',
    recommendedNextAction: 'Wait and check checkout status before trying again.',
    safetyWarning: 'Do not share payment credentials.',
    adviceSource: 'GEMINI',
  };

  beforeEach(async () => {
    previousPayPal = window.paypal;
    buttonOptions = undefined;
    window.paypal = {
      Buttons: (options) => {
        buttonOptions = options;
        return {
          render: (container) => {
            const button = document.createElement('button');
            button.className = 'mock-paypal-button';
            button.textContent = 'PayPal';
            container.appendChild(button);
            return Promise.resolve();
          },
        };
      },
    };

    api = jasmine.createSpyObj<CheckoutApiService>('CheckoutApiService', [
      'getProducts',
      'getPayPalConfiguration',
      'createCheckoutSession',
      'createPayPalOrder',
      'capturePayPalOrder',
      'cancelPayPalOrder',
      'getRecoveryAdvice',
    ]);
    api.getProducts.and.resolveTo([product]);
    api.getPayPalConfiguration.and.resolveTo({ clientId: 'sandbox-client', currency: 'USD' });
    api.createCheckoutSession.and.resolveTo(session);
    api.createPayPalOrder.and.resolveTo({ paypalOrderId: 'ORDER-123' });
    api.getRecoveryAdvice.and.resolveTo(recoveryAdvice);

    await TestBed.configureTestingModule({
      imports: [StorefrontComponent],
      providers: [provideRouter([]), { provide: CheckoutApiService, useValue: api }],
    }).compileComponents();
  });

  afterEach(() => {
    window.paypal = previousPayPal;
  });

  async function createFixture(): Promise<ComponentFixture<StorefrontComponent>> {
    const fixture = TestBed.createComponent(StorefrontComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
    return fixture;
  }

  function paymentResult(
    status: PaymentResult['status'],
    overrides: Partial<PaymentResult> = {},
  ): PaymentResult {
    return {
      checkoutSessionId: session.id,
      paypalOrderId: 'ORDER-123',
      status,
      amount: session.total,
      currency: session.currency,
      message: `Backend status: ${status}`,
      ...overrides,
    };
  }

  function enterRecoveryProblem(element: HTMLElement, description: string): void {
    const textarea = element.querySelector<HTMLTextAreaElement>('#recovery-problem')!;
    textarea.value = description;
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
  }

  async function submitRecovery(
    element: HTMLElement,
    fixture: ComponentFixture<StorefrontComponent>,
  ): Promise<void> {
    element.querySelector<HTMLButtonElement>('.recovery-submit')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('creates the app and renders the CartBridge checkout experience', async () => {
    const fixture = await createFixture();

    expect(fixture.componentInstance).toBeTruthy();
    expect(fixture.nativeElement.querySelector('h1')?.textContent).toContain('Good finds deserve');
    expect(fixture.nativeElement.querySelector('.mock-paypal-button')).toBeTruthy();
  });

  it('shows CREATED then APPROVED while verifying, hides buttons after completion, and allows a new checkout', async () => {
    api.capturePayPalOrder.and.resolveTo(paymentResult('COMPLETED', {
      paypalCaptureId: 'CAPTURE-VERIFIED-123',
      message: 'Payment completed and verified by PayPal.',
    }));
    const fixture = await createFixture();
    const element = fixture.nativeElement as HTMLElement;

    await buttonOptions!.createOrder();
    fixture.detectChanges();
    expect(element.querySelector('.payment-result strong')?.textContent).toContain('Checkout ready');
    expect(element.querySelector('.paypal-buttons')?.hasAttribute('hidden')).toBeFalse();

    const approval = buttonOptions!.onApprove({ orderID: 'ORDER-123' });
    fixture.detectChanges();
    expect(element.querySelector('.payment-result strong')?.textContent).toContain('Approval received');
    await approval;
    fixture.detectChanges();

    expect(api.capturePayPalOrder).toHaveBeenCalledOnceWith(session.id, 'ORDER-123');
    expect(element.querySelector('.payment-result strong')?.textContent).toContain('Payment verified');
    expect(element.querySelector('.payment-result small')?.textContent)
      .toContain('CAPTURE-VERIFIED-123');
    expect(element.querySelector('.paypal-buttons')?.hasAttribute('hidden')).toBeTrue();
    expect(element.querySelector('.new-checkout-button')).toBeTruthy();
    expect(element.querySelector<HTMLButtonElement>('.product-card')?.disabled).toBeTrue();

    element.querySelector<HTMLButtonElement>('.new-checkout-button')?.click();
    fixture.detectChanges();
    expect(element.querySelector('.payment-result')).toBeNull();
    expect(element.querySelector('.paypal-buttons')?.hasAttribute('hidden')).toBeFalse();
    expect(element.querySelector('.product-card')?.hasAttribute('disabled')).toBeFalse();
  });

  it('renders cancelled, failed, and pending outcomes from the backend', async () => {
    const fixture = await createFixture();
    const element = fixture.nativeElement as HTMLElement;
    const outcomes: Array<[PaymentResult['status'], string]> = [
      ['CANCELLED', 'Checkout cancelled'],
      ['FAILED', 'Payment failed'],
      ['PENDING', 'Payment pending'],
    ];

    for (const [status, label] of outcomes) {
      api.capturePayPalOrder.and.resolveTo(paymentResult(status));
      await buttonOptions!.createOrder();
      await buttonOptions!.onApprove({ orderID: 'ORDER-123' });
      fixture.detectChanges();
      expect(element.querySelector('.payment-result strong')?.textContent).toContain(label);
    }
  });

  it('reports buyer cancellation using the backend status', async () => {
    api.cancelPayPalOrder.and.resolveTo(paymentResult('CANCELLED'));
    const fixture = await createFixture();
    const element = fixture.nativeElement as HTMLElement;

    await buttonOptions!.createOrder();
    await buttonOptions!.onCancel({ orderID: 'ORDER-123' });
    fixture.detectChanges();

    expect(api.cancelPayPalOrder).toHaveBeenCalledOnceWith(session.id, 'ORDER-123');
    expect(element.querySelector('.payment-result strong')?.textContent).toContain('Checkout cancelled');
  });

  it('validates an empty recovery problem without making API calls', async () => {
    const fixture = await createFixture();
    const element = fixture.nativeElement as HTMLElement;

    await submitRecovery(element, fixture);

    expect(element.querySelector('.recovery-error')?.textContent)
      .toContain('Describe the checkout problem');
    expect(api.createCheckoutSession).not.toHaveBeenCalled();
    expect(api.getRecoveryAdvice).not.toHaveBeenCalled();
  });

  it('shows all fields returned by the recovery advice API', async () => {
    const fixture = await createFixture();
    const element = fixture.nativeElement as HTMLElement;
    enterRecoveryProblem(element, 'The checkout page stopped responding.');

    await submitRecovery(element, fixture);

    expect(api.createCheckoutSession).toHaveBeenCalledOnceWith(product.id, 1);
    expect(api.getRecoveryAdvice).toHaveBeenCalledOnceWith(session.id, 'The checkout page stopped responding.');
    expect(element.querySelector('.recovery-advice')?.textContent).toContain(recoveryAdvice.detectedIssue);
    expect(element.querySelector('.recovery-advice')?.textContent).toContain(recoveryAdvice.explanation);
    expect(element.querySelector('.recovery-advice')?.textContent)
      .toContain(recoveryAdvice.recommendedNextAction);
    expect(element.querySelector('.recovery-advice')?.textContent).toContain(recoveryAdvice.safetyWarning);
    expect(element.querySelector('.advice-source')?.textContent).toContain('AI-generated');
  });

  it('labels backend fallback guidance distinctly from AI advice', async () => {
    api.getRecoveryAdvice.and.resolveTo({
      ...recoveryAdvice,
      adviceSource: 'FALLBACK',
    });
    const fixture = await createFixture();
    const element = fixture.nativeElement as HTMLElement;
    enterRecoveryProblem(element, 'Checkout is stuck.');

    await submitRecovery(element, fixture);

    expect(element.querySelector('.advice-source')?.textContent).toContain('Fallback guidance');
  });

  it('reuses an existing checkout session for recovery advice', async () => {
    const fixture = await createFixture();
    await buttonOptions!.createOrder();
    const element = fixture.nativeElement as HTMLElement;
    enterRecoveryProblem(element, 'The payment page froze.');

    await submitRecovery(element, fixture);

    expect(api.createCheckoutSession).toHaveBeenCalledOnceWith(product.id, 1);
    expect(api.getRecoveryAdvice).toHaveBeenCalledOnceWith(
      session.id,
      'The payment page froze.',
    );
  });

  it('shows recovery API errors without changing PayPal state', async () => {
    api.getRecoveryAdvice.and.rejectWith(
      new HttpErrorResponse({ status: 503, error: { error: 'Gemini advice is unavailable.' } }),
    );
    const fixture = await createFixture();
    const element = fixture.nativeElement as HTMLElement;
    enterRecoveryProblem(element, 'Checkout is stuck.');

    await submitRecovery(element, fixture);

    expect(element.querySelector('.recovery-error')?.textContent)
      .toContain('Gemini advice is unavailable.');
    expect(element.querySelector('.payment-result')).toBeNull();
    expect(element.querySelector('.mock-paypal-button')).toBeTruthy();
    expect(api.createPayPalOrder).not.toHaveBeenCalled();
    expect(api.capturePayPalOrder).not.toHaveBeenCalled();
  });

  it('shows a helpful message when recovery advice times out and clears loading', async () => {
    api.getRecoveryAdvice.and.rejectWith(new TimeoutError());
    const fixture = await createFixture();
    const element = fixture.nativeElement as HTMLElement;
    enterRecoveryProblem(element, 'Checkout is stuck.');

    await submitRecovery(element, fixture);

    expect(element.querySelector('.recovery-error')?.textContent)
      .toContain('AI assistance is taking too long. Please try again.');
    expect(element.querySelector('.recovery-loading')).toBeNull();
    expect(element.querySelector('.mock-paypal-button')).toBeTruthy();
    expect(api.createPayPalOrder).not.toHaveBeenCalled();
    expect(api.capturePayPalOrder).not.toHaveBeenCalled();
  });

  it('prevents duplicate recovery submissions while advice is pending', async () => {
    let resolveAdvice!: (advice: CheckoutRecoveryAdvice) => void;
    api.getRecoveryAdvice.and.returnValue(
      new Promise<CheckoutRecoveryAdvice>((resolve) => {
        resolveAdvice = resolve;
      }),
    );
    const fixture = await createFixture();
    const element = fixture.nativeElement as HTMLElement;
    enterRecoveryProblem(element, 'Checkout is stuck.');

    element.querySelector<HTMLButtonElement>('.recovery-submit')!.click();
    fixture.detectChanges();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
    expect(api.getRecoveryAdvice).toHaveBeenCalledTimes(1);

    element.querySelector<HTMLButtonElement>('.recovery-submit')!.click();
    fixture.detectChanges();

    expect(api.getRecoveryAdvice).toHaveBeenCalledTimes(1);
    expect(element.querySelector('.recovery-loading')).toBeTruthy();
    expect(element.querySelector('.checkout-progress')).toBeNull();
    expect(element.querySelector('.mock-paypal-button')).toBeTruthy();

    resolveAdvice(recoveryAdvice);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(element.querySelector('.recovery-advice')).toBeTruthy();
  });

  it('rejects a recovery description longer than 1000 characters', async () => {
    const fixture = await createFixture();
    const element = fixture.nativeElement as HTMLElement;
    enterRecoveryProblem(element, 'x'.repeat(1001));

    await submitRecovery(element, fixture);

    expect(element.querySelector('.recovery-error')?.textContent).toContain('1000 characters or fewer');
    expect(api.getRecoveryAdvice).not.toHaveBeenCalled();
  });

  it('does not send card details or secrets to the recovery API', async () => {
    const fixture = await createFixture();
    const element = fixture.nativeElement as HTMLElement;
    enterRecoveryProblem(element, 'My card number is 4111 1111 1111 1111.');

    await submitRecovery(element, fixture);

    expect(element.querySelector('.recovery-error')?.textContent)
      .toContain('Remove payment details');
    expect(api.getRecoveryAdvice).not.toHaveBeenCalled();
  });
});
