import type { Command, Priority } from '../../nexus/types';

export interface OrderForm {
  productId: string;
  quantity: number;
  priority: Priority;
  dueInMinutes: number;
}

export const newOrderForm = (productId: string): OrderForm => ({ productId, quantity: 10, priority: 'NORMAL', dueInMinutes: 240 });

export function orderCommand(f: OrderForm): Command {
  return { command: 'createOrder', productId: f.productId, quantity: f.quantity, priority: f.priority, dueInMinutes: f.dueInMinutes };
}

/** Accepted: a fresh form for the same product. Refused (send shows the reason): the form as the user left it. */
export async function submitOrder(f: OrderForm, send: (c: Command) => Promise<boolean>): Promise<OrderForm> {
  return (await send(orderCommand(f))) ? newOrderForm(f.productId) : f;
}
