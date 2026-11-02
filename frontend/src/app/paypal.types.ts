export interface PayPalConfiguration {
  clientId: string;
  currency: string;
}

export interface PayPalButtonData {
  orderID: string;
}

export interface PayPalButtonsOptions {
  style?: {
    layout?: 'vertical' | 'horizontal';
    color?: 'gold' | 'blue' | 'silver' | 'black' | 'white';
    shape?: 'rect' | 'pill';
    label?: 'paypal' | 'checkout' | 'buynow' | 'pay' | 'installment';
    height?: number;
  };
  createOrder: () => Promise<string>;
  onApprove: (data: PayPalButtonData) => Promise<void>;
  onCancel: (data: PayPalButtonData) => Promise<void>;
  onError: (error: unknown) => void;
}

export interface PayPalSdk {
  Buttons(options: PayPalButtonsOptions): {
    render(container: HTMLElement): Promise<void>;
  };
}

declare global {
  interface Window {
    paypal?: PayPalSdk;
  }
}
