import 'package:flutter_test/flutter_test.dart';
import 'package:hey_bro/main.dart';

void main() {
  testWidgets('Hey Bro app launches without crashing', (WidgetTester tester) async {
    await tester.pumpWidget(const HeyBroApp());
    expect(find.text('Hey Bro'), findsWidgets);
  });
}
