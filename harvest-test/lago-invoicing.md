> ## Documentation Index
> Fetch the complete documentation index at: https://getlago.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Invoicing: generate invoices from plans and usage

> Lago automatically generates invoices for each customer according to the plan model.

When a new invoice is generated, Lago also sends an `invoice.created` webhook
([learn more](https://getlago.com/docs/api-reference/webhooks/messages.md)).

When an invoice is linked to an add-on, another type of webhook is sent, called
`invoice.one_off_created`.

## Invoice object

Invoices include (but are not limited to):

* Invoice number;
* Billing period;
* Customer information;
* Fees; and
* Taxes.

You can [download invoices](https://getlago.com/docs/guide/invoicing/download-invoices.md) or use the
[invoice object](https://getlago.com/docs/api-reference/invoices/invoice-object.md) to create your own invoice
template.

For payment collection, connect a [native payment provider](https://getlago.com/docs/guide/payments/payment-providers.md). For accounting, compare the [NetSuite](https://getlago.com/docs/integrations/accounting/netsuite.md) and [Xero](https://getlago.com/docs/integrations/accounting/xero.md) integrations, which are available on request, with the [community-maintained QuickBooks integration](https://getlago.com/docs/integrations/accounting/quickbooks.md). You can also use invoice webhooks and the API for a custom workflow.


This documentation is built and hosted on [Mintlify](https://mintlify.com), a developer documentation platform.