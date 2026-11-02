import { Component, ElementRef, OnInit, signal, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { TimeoutError } from 'rxjs';
import { RouterLink } from '@angular/router';
import {
  CheckoutApiService,
  CheckoutRecoveryAdvice,
  CheckoutSession,
  PaymentResult,
  Product,
} from './checkout-api.service';
import { PayPalConfiguration, PayPalSdk } from './paypal.types';

@Component({
  selector: 'app-storefront',
  imports: [FormsModule, RouterLink],
  templateUrl: './storefront.html',
  styleUrl: './storefront.scss',
})
export class StorefrontComponent implements OnInit {
  private readonly sensitiveDetailsPattern =
    /\b\d(?:[ -]?\d){12,18}\b|\b(?:api[\s_-]?key|access[\s_-]?token|secret|password|passcode|otp|cvv|cvc|security code|paypal credentials?)\s*[:=]\s*\S+/i;

  @ViewChild('paypalButtons')
  set paypalButtons(element: ElementRef<HTMLDivElement> | undefined) {
    this.paypalButtonsElement = element;
    void this.renderPayPalButtons();
  }

  protected readonly products = signal<Product[]>([]);
  protected readonly selectedProductId = signal('');
  protected readonly quantity = signal(1);
  protected readonly configuration = signal<PayPalConfiguration | null>(null);
  protected readonly checkoutSession = signal<CheckoutSession | null>(null);
  protected readonly paymentResult = signal<PaymentResult | null>(null);
  protected readonly loadingProducts = signal(true);
  protected readonly loadingPayPal = signal(true);
  protected readonly busy = signal(false);
  protected readonly errorMessage = signal('');
  protected readonly sdkError = signal('');
  protected readonly problemDescription = signal('');
  protected readonly isRecoveryLoading = signal(false);
  protected readonly recoveryAdvice = signal<CheckoutRecoveryAdvice | null>(null);
  protected readonly recoveryError = signal('');

  private paypalButtonsElement?: ElementRef<HTMLDivElement>;
  private sdkRenderStarted = false;

  constructor(private readonly checkoutApi: CheckoutApiService) {}

  async ngOnInit(): Promise<void> {
    await Promise.all([this.loadProducts(), this.loadPayPalConfiguration()]);
  }

  protected selectProduct(productId: string): void {
    if (this.busy() || this.paymentResult()?.status === 'COMPLETED') return;
    this.selectedProductId.set(productId);
    this.paymentResult.set(null);
    this.checkoutSession.set(null);
  }

  protected startNewCheckout(): void {
    if (this.busy()) return;
    this.paymentResult.set(null);
    this.checkoutSession.set(null);
    this.errorMessage.set('');
  }

  protected updateQuantity(value: string): void {
    if (this.busy() || this.paymentResult()?.status === 'COMPLETED') return;
    const quantity = Number(value);
    if (Number.isInteger(quantity) && quantity >= 1 && quantity <= 10) {
      this.quantity.set(quantity);
    }
  }

  protected updateProblemDescription(event: Event): void {
    this.problemDescription.set((event.target as HTMLTextAreaElement).value);
  }

  protected async submitRecoveryRequest(): Promise<void> {
    if (this.isRecoveryLoading()) return;

    const problemDescription = this.problemDescription().trim();
    if (!problemDescription) {
      this.recoveryError.set('Describe the checkout problem before requesting advice.');
      this.recoveryAdvice.set(null);
      return;
    }
    if (problemDescription.length > 1000) {
      this.recoveryError.set('Keep the problem description to 1000 characters or fewer.');
      this.recoveryAdvice.set(null);
      return;
    }
    if (this.sensitiveDetailsPattern.test(problemDescription)) {
      this.recoveryError.set(
        'Remove payment details, passwords, codes, or secrets before requesting advice.',
      );
      this.recoveryAdvice.set(null);
      return;
    }

    const product = this.selectedProduct();
    if (!this.checkoutSession() && !product) {
      this.recoveryError.set('Choose a product before requesting checkout advice.');
      this.recoveryAdvice.set(null);
      return;
    }

    this.isRecoveryLoading.set(true);
    this.recoveryError.set('');
    this.recoveryAdvice.set(null);

    try {
      let checkoutSession = this.checkoutSession();
      if (!checkoutSession && product) {
        checkoutSession = await this.checkoutApi.createCheckoutSession(product.id, this.quantity());
        if (!this.checkoutSession()) {
          this.checkoutSession.set(checkoutSession);
        }
      }
      if (!checkoutSession) {
        throw new Error('Could not prepare a checkout session for recovery advice.');
      }

      const advice = await this.checkoutApi.getRecoveryAdvice(
        checkoutSession.id,
        problemDescription,
      );
      this.recoveryAdvice.set(advice);
    } catch (error) {
      this.recoveryError.set(error instanceof TimeoutError
        ? 'AI assistance is taking too long. Please try again.'
        : this.errorMessageFrom(error, 'Checkout recovery advice is currently unavailable.'));
    } finally {
      this.isRecoveryLoading.set(false);
    }
  }

  protected selectedProduct(): Product | undefined {
    return this.products().find((product) => product.id === this.selectedProductId());
  }

  protected total(): number {
    return (this.selectedProduct()?.price ?? 0) * this.quantity();
  }

  protected formatPrice(amount: number, currency: string): string {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount);
  }

  protected statusClass(): string {
    return (this.paymentResult()?.status ?? '').toLowerCase();
  }

  protected statusLabel(): string {
    switch (this.paymentResult()?.status) {
      case 'CREATED': return 'Checkout ready';
      case 'APPROVED': return 'Approval received';
      case 'COMPLETED': return 'Payment verified';
      case 'PENDING': return 'Payment pending';
      case 'FAILED': return 'Payment failed';
      case 'CANCELLED': return 'Checkout cancelled';
      default: return '';
    }
  }

  private async loadProducts(): Promise<void> {
    try {
      const products = await this.checkoutApi.getProducts();
      this.products.set(products);
      this.selectedProductId.set(products[0]?.id ?? '');
    } catch (error) {
      this.errorMessage.set(this.errorMessageFrom(error, 'Could not load the product catalogue.'));
    } finally {
      this.loadingProducts.set(false);
    }
  }

  private async loadPayPalConfiguration(): Promise<void> {
    try {
      const configuration = await this.checkoutApi.getPayPalConfiguration();
      this.configuration.set(configuration);
      await this.loadPayPalSdk(configuration);
    } catch (error) {
      this.sdkError.set(this.errorMessageFrom(error, 'PayPal Sandbox is not configured.'));
    } finally {
      this.loadingPayPal.set(false);
    }
  }

  private async loadPayPalSdk(configuration: PayPalConfiguration): Promise<void> {
    const existingScript = document.querySelector<HTMLScriptElement>('#paypal-sdk');
    if (window.paypal) {
      return;
    }
    if (existingScript) {
      await new Promise<void>((resolve, reject) => {
        existingScript.addEventListener('load', () => resolve(), { once: true });
        existingScript.addEventListener('error', () => reject(new Error('PayPal SDK failed to load.')), {
          once: true,
        });
      });
      if (window.paypal) return;
      throw new Error('PayPal SDK did not initialize.');
    }

    const query = new URLSearchParams({
      'client-id': configuration.clientId,
      currency: configuration.currency,
      intent: 'capture',
      components: 'buttons',
    });
    await new Promise<void>((resolve, reject) => {
      const script = document.createElement('script');
      script.id = 'paypal-sdk';
      script.src = `https://www.paypal.com/sdk/js?${query.toString()}`;
      script.onload = () => resolve();
      script.onerror = () => reject(new Error('PayPal SDK failed to load.'));
      document.head.appendChild(script);
    });
    if (!window.paypal) {
      throw new Error('PayPal SDK did not initialize.');
    }
    void this.renderPayPalButtons();
  }

  private async renderPayPalButtons(): Promise<void> {
    if (!this.paypalButtonsElement || !window.paypal || this.sdkRenderStarted) {
      return;
    }
    this.sdkRenderStarted = true;
    try {
      await window.paypal.Buttons({
        style: { layout: 'vertical', color: 'gold', shape: 'rect', label: 'paypal', height: 48 },
        createOrder: () => this.createOrder(),
        onApprove: (data) => this.captureOrder(data.orderID),
        onCancel: (data) => this.cancelOrder(data.orderID),
        onError: (error) => {
          this.busy.set(false);
          this.errorMessage.set(this.errorMessageFrom(error, 'PayPal checkout could not be completed.'));
        },
      }).render(this.paypalButtonsElement.nativeElement);
    } catch (error) {
      this.sdkRenderStarted = false;
      this.sdkError.set(this.errorMessageFrom(error, 'PayPal buttons could not be displayed.'));
    }
  }

  private async createOrder(): Promise<string> {
    if (this.busy() || this.paymentResult()?.status === 'COMPLETED') {
      throw new Error('A checkout request is already in progress.');
    }
    const product = this.selectedProduct();
    if (!product) {
      throw new Error('Select a product before starting checkout.');
    }
    this.busy.set(true);
    this.errorMessage.set('');
    this.paymentResult.set(null);
    try {
      const session = await this.checkoutApi.createCheckoutSession(product.id, this.quantity());
      this.checkoutSession.set(session);
      const order = await this.checkoutApi.createPayPalOrder(session.id);
      this.paymentResult.set({
        checkoutSessionId: session.id,
        paypalOrderId: order.paypalOrderId,
        status: 'CREATED',
        amount: session.total,
        currency: session.currency,
        message: 'Checkout is ready. Buyer approval in PayPal is required before payment.',
      });
      return order.paypalOrderId;
    } catch (error) {
      this.busy.set(false);
      this.errorMessage.set(this.errorMessageFrom(error, 'Could not start PayPal checkout.'));
      throw error;
    }
  }

  private captureInProgress = false;

  private async captureOrder(paypalOrderId: string): Promise<void> {
    if (this.paymentResult()?.status === 'COMPLETED' || this.captureInProgress) return;
    const session = this.checkoutSession();
    if (!session) {
      this.busy.set(false);
      this.errorMessage.set('Checkout session was not found. Please start checkout again.');
      return;
    }
    this.captureInProgress = true;
    this.paymentResult.set({
      checkoutSessionId: session.id,
      paypalOrderId,
      status: 'APPROVED',
      amount: session.total,
      currency: session.currency,
      message: 'PayPal approval received. Verifying the capture with CartBridge.',
    });
    try {
      const result = await this.checkoutApi.capturePayPalOrder(session.id, paypalOrderId);
      this.paymentResult.set(result);
    } catch (error) {
      this.errorMessage.set(this.errorMessageFrom(error, 'The payment could not be verified.'));
    } finally {
      this.captureInProgress = false;
      this.busy.set(false);
    }
  }

  private async cancelOrder(paypalOrderId: string): Promise<void> {
    const session = this.checkoutSession();
    if (!session) {
      this.busy.set(false);
      return;
    }
    try {
      this.paymentResult.set(await this.checkoutApi.cancelPayPalOrder(session.id, paypalOrderId));
    } catch (error) {
      this.errorMessage.set(this.errorMessageFrom(error, 'Checkout was cancelled.'));
    } finally {
      this.busy.set(false);
    }
  }

  private errorMessageFrom(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse && typeof error.error === 'object' && error.error !== null) {
      const body = error.error as { error?: unknown; message?: unknown };
      if (typeof body.error === 'string') return body.error;
      if (typeof body.message === 'string') return body.message;
    }
    if (error instanceof Error && error.message) return error.message;
    if (typeof error === 'object' && error !== null && 'error' in error) {
      const response = (error as { error?: { error?: string; message?: string } }).error;
      if (response?.error) return response.error;
      if (response?.message) return response.message;
    }
    return fallback;
  }
}
