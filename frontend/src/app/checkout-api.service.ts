import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';
import { PayPalConfiguration } from './paypal.types';

const API_URL = 'http://localhost:8080/api';
const RECOVERY_REQUEST_TIMEOUT_MS = 35_000;

export interface Product {
  id: string;
  name: string;
  description: string;
  price: number;
  currency: string;
}

export interface CheckoutSession {
  id: string;
  productId: string;
  productName: string;
  quantity: number;
  unitPrice: number;
  total: number;
  currency: string;
  status: 'CREATED' | 'APPROVED' | 'COMPLETED' | 'PENDING' | 'FAILED' | 'CANCELLED';
  createdAt?: string;
  paypalOrderId?: string;
  paypalCaptureId?: string;
}

export interface MerchantDashboardResponse {
  totalCheckoutSessions: number;
  statusCounts: Record<CheckoutSession['status'], number>;
  checkouts: CheckoutSession[];
}

export interface CheckoutRecoveryHistoryEntry {
  checkoutSessionId: string;
  problemDescription: string;
  detectedIssue: string;
  explanation: string;
  recommendedNextAction: string;
  safetyWarning: string;
  adviceSource: 'GEMINI' | 'FALLBACK';
  timestamp: string;
  paymentStatus: CheckoutSession['status'];
}

export interface PaymentResult {
  checkoutSessionId: string;
  paypalOrderId: string;
  paypalCaptureId?: string;
  status: 'CREATED' | 'APPROVED' | 'COMPLETED' | 'PENDING' | 'FAILED' | 'CANCELLED';
  amount: number;
  currency: string;
  message: string;
}

export interface CheckoutRecoveryRequest {
  checkoutSessionId: string;
  problemDescription: string;
}

export interface CheckoutRecoveryAdvice {
  detectedIssue: string;
  explanation: string;
  recommendedNextAction: string;
  safetyWarning: string;
  adviceSource: 'GEMINI' | 'FALLBACK';
}

@Injectable({ providedIn: 'root' })
export class CheckoutApiService {
  private readonly http = inject(HttpClient);

  getProducts(): Promise<Product[]> {
    return firstValueFrom(this.http.get<Product[]>(`${API_URL}/products`));
  }

  getPayPalConfiguration(): Promise<PayPalConfiguration> {
    return firstValueFrom(this.http.get<PayPalConfiguration>(`${API_URL}/paypal/config`));
  }

  createCheckoutSession(productId: string, quantity: number): Promise<CheckoutSession> {
    return firstValueFrom(
      this.http.post<CheckoutSession>(`${API_URL}/checkout-sessions`, { productId, quantity }),
    );
  }

  createPayPalOrder(checkoutSessionId: string): Promise<{ paypalOrderId: string }> {
    return firstValueFrom(
      this.http.post<{ paypalOrderId: string }>(`${API_URL}/paypal/orders`, { checkoutSessionId }),
    );
  }

  capturePayPalOrder(checkoutSessionId: string, paypalOrderId: string): Promise<PaymentResult> {
    return firstValueFrom(
      this.http.post<PaymentResult>(`${API_URL}/paypal/capture`, { checkoutSessionId, paypalOrderId }),
    );
  }

  cancelPayPalOrder(checkoutSessionId: string, paypalOrderId: string): Promise<PaymentResult> {
    return firstValueFrom(
      this.http.post<PaymentResult>(`${API_URL}/paypal/cancel`, { checkoutSessionId, paypalOrderId }),
    );
  }

  getRecoveryAdvice(
    checkoutSessionId: string,
    problemDescription: string,
  ): Promise<CheckoutRecoveryAdvice> {
    const request: CheckoutRecoveryRequest = { checkoutSessionId, problemDescription };
    return firstValueFrom(
      this.http
        .post<CheckoutRecoveryAdvice>(`${API_URL}/recovery/advice`, request)
        .pipe(timeout({ first: RECOVERY_REQUEST_TIMEOUT_MS })),
    );
  }

  getMerchantDashboard(): Promise<MerchantDashboardResponse> {
    return firstValueFrom(
      this.http.get<MerchantDashboardResponse>(`${API_URL}/merchant/dashboard`),
    );
  }

  getMerchantRecoveryHistory(): Promise<CheckoutRecoveryHistoryEntry[]> {
    return firstValueFrom(
      this.http.get<CheckoutRecoveryHistoryEntry[]>(`${API_URL}/merchant/recovery-history`),
    );
  }
}
