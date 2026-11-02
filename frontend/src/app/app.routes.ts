import { Routes } from '@angular/router';
import { StorefrontComponent } from './storefront';
import { MerchantDashboardComponent } from './merchant-dashboard';

export const routes: Routes = [
  { path: '', component: StorefrontComponent, pathMatch: 'full' },
  { path: 'dashboard', component: MerchantDashboardComponent },
  { path: '**', redirectTo: '' },
];
